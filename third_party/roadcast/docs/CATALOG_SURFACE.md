# Catalog Surface

This document records what the OEM VHAL binary exposes, how much of it the
Roadcast catalog covers, and where the remaining unexposed data is. It answers a
recurring question: are there CAN signals sitting in VHAL memory that the
current signal map does not reach?

The short answer: the receive side is exhausted, but two separate reservoirs
remain. One is reachable by static extraction; the other is not.

## Method

Static, read-only analysis of the VHAL executable. No device, no vehicle, no
runtime observation. The analysis parses the ELF symbol tables for CAN buffer
symbols and the DWARF `.debug_str` section for per-signal accessor names, then
diffs both against `data/dbc.json`.

Reproduce with:

```bash
scripts/analyze-vhal-surface.py <path-to-vhal-binary>
```

The script exits non-zero when the receive-side surface diverges from the
catalog, so it doubles as a regression check after a VHAL update.

## Analyzed Subject

```text
file:     android.hardware.automotive.vehicle@2.0-service
BuildID:  06983bde9087f73982929a12295303df
format:   ELF64 aarch64, dynamically linked, not stripped, with debug_info
size:     22289376 bytes
analyzed: 2026-07-26
```

Findings are valid only for this build. An OTA that updates the VHAL invalidates
every count below, and can also strip the symbol table, which would break source
discovery entirely rather than merely shifting addresses.

## Finding 1: The Receive-Side Buffer Set Is Complete

The binary exposes exactly 111 `MMI_Rx_XXX` buffer symbols. The diff against the
catalog is empty in both directions: no buffer is missing from `dbc.json`, and
no catalog frame lacks a symbol.

The daemon's resolver cannot discover new frames on its own. `resolve_symbols()`
iterates the symbol table but compares each name against `ROADCAST_CAN_IDS`, so
an unknown `MMI_Rx_*` symbol would be seen and discarded silently. That blindness
is currently harmless because the sets are identical, but it is why this check
must be run externally rather than inferred from a successful daemon startup.

## Finding 2: Buffer Geometry Confirms Three Implicit Assumptions

| Assumption in code | Verified |
| --- | --- |
| Every buffer is 8 bytes (`iov_len = 8`) | All 155 buffers report `st_size == 8` |
| `can_id` fits in `uint16_t` | No buffer ID exceeds `0x7FF`; no extended 29-bit IDs |
| Buffers are independent addresses | Both blocks are gap-free and mutually adjacent |

The address layout is a single contiguous region:

```text
Rx block  0xbd6e58 .. 0xbd71d0   888 bytes   111 buffers
Tx block  0xbd71d0 .. 0xbd7330   352 bytes    44 buffers
                                 ----------
total     0xbd6e58 .. 0xbd7330  1240 bytes   155 buffers
```

The Rx block ends exactly where the Tx block begins. A single 1240-byte remote
read covers every buffer, which is cheaper than the 111 separate iovecs the
sampler builds today.

## Finding 3: The Receive-Side Signal Map Is Also Complete

The binary names per-signal accessors as `ILGetRx_MMI_<CANID>_<Signal>` and
`ILCheckRx_MMI_<CANID>_<Signal>`. Normalizing those to `(can_id, name)` pairs
yields 818 receive-side signals, of which 813 are already in the catalog. The
five remainders belong to frames that are not receive buffers.

Zero missing signals land on a frame Roadcast already reads. The extraction is
exhaustive with respect to DWARF, and no further static extraction will add
receive-side signals.

Two catalog signals have no corresponding accessor string. They are harmless but
unexplained; they may come from the calibration overlay rather than from DWARF.

## Finding 4: 61 Percent of the Bits Already Being Read Are Unmapped

Across the 111 frames Roadcast reads, only 2741 of 7104 bits are claimed by a
catalog signal.

```text
total bits available     7104
claimed by signals       2741   (38.6%)
unclaimed                4363   (61.4%)
overlapping assignments     0
frames fully mapped         6
```

Four frames (`0x150`, `0x160`, `0x176`, `0x2CD`) have 63 of 64 bits unmapped.

Two properties of this result matter more than the headline number.

First, there are **zero overlapping bit assignments**. No two signals claim the
same bit. That is strong evidence the existing map is internally consistent and
was extracted correctly, not guessed.

Second, because Finding 3 shows DWARF is exhausted, these 4363 bits are not
signals the extraction missed. The binary genuinely does not define them. They
are either reserved or padding, or they carry data that other ECUs consume and
the head unit never decodes.

The consequence is a hard boundary on method: **these bits cannot be recovered
by static analysis.** Names and scales are not present in the binary at all.
They are discoverable only empirically, by observing which unclaimed bits change
while the vehicle is operating. Behaviour is recoverable; OEM naming is not.

This bounds the ceiling of the catalog honestly. It also connects to a known
defect: bit-toggle discovery needs per-frame "last changed" timestamps, which is
the same freshness infrastructure whose absence currently makes the `valid` flag
mean "symbol resolved" rather than "observed".

## Finding 5: The Transmit Side Is a Large, Unexploited Static Reservoir

The binary exposes 44 `MMI_Tx_XXX` buffers holding what the head unit transmits.
None of them overlaps the receive catalog, and DWARF defines signals for them
through `ILPutTx_*`, `ILGetTx_*`, and `ILRstTx_*` accessors.

```text
Tx buffers                        44   (8 bytes each, zero overlap with Rx)
Tx signals defined in DWARF      600
  of those, on Tx buffers        580   across 41 of the 44 buffers
  overlapping current catalog      0
richest frames        0x2A5 (36), 0x2AA (34), 0x2A7 (32), 0x2A2 (29)
buffers with no signals    0x407, 0x72C, 0x7C9
```

Adopting this would grow the catalog by roughly 40 percent in frames and 71
percent in signals, using the extraction pipeline that is already proven, with
no new reverse engineering.

Twenty further Tx signals reference frame IDs that are not Tx buffers (`0x0E0`,
`0x0F1`-`0x0F8`, `0x0FB`). `0x0E0` is a receive frame in the current catalog, so
these likely use a different mechanism and should be understood before being
treated as part of the Tx set.

### Reading Transmit Buffers Is Still Read-Only

The read-only invariant forbids writing VHAL properties, writing into the VHAL
process, transmitting CAN frames, and invoking vehicle controls. Reading a
transmit buffer does none of those. It observes what the head unit has already
decided to send, in exactly the same way the receive buffers observe what other
ECUs sent.

The distinction is worth stating explicitly because the word "transmit" invites
the opposite reading. Exposing these buffers adds no write path, and the code
required is the existing remote-read path pointed at a different address range.

## What This Analysis Does Not Establish

- Whether any transmit signal is physically meaningful or correctly scaled.
  Scale and unit calibration is a separate, empirical problem; the current
  catalog has only two calibrated signals out of 815.
- Whether the unclaimed bits carry data at all. That requires vehicle
  observation.
- Whether the VHAL still exposes these symbols on other vehicle software
  versions. Every count here is pinned to one BuildID.
- Whether reading the transmit block is permitted under an enforcing SELinux
  policy. That constraint applies to the entire source path, not to this
  finding specifically.

## Consequences for the Roadmap

The catalog cannot be grown by looking harder at the receive side. There are
exactly three ways forward, in increasing cost:

1. **Adopt the transmit surface.** 44 buffers and 580 signals, statically
   defined, contiguous with the block already being read. Needs a decision on
   exposure, and a schema that distinguishes observed-transmit from
   observed-receive.
2. **Calibrate what already exists.** 813 of 815 signals carry `scale=1.0`,
   `offset=0`, and no unit. This is where product value is currently bottlenecked,
   and it is unaffected by anything in this document.
3. **Discover unmapped bits empirically.** 4363 bits, no names available,
   requires vehicle time and per-frame change timestamps.
