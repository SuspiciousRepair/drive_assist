#!/usr/bin/env python3
"""Report the CAN surface an OEM VHAL binary exposes, and diff it against data/dbc.json.

This is a static, read-only analysis. It answers two questions:

  1. Does the catalog cover every CAN buffer the VHAL binary exposes as a symbol?
  2. Does the catalog cover every signal the binary's DWARF strings define?

Re-run it after any VHAL update: an OTA can add buffers, rename symbols, or strip
the symbol table, and each of those silently changes what Roadcast can observe.

Exit status is 2 when the receive-side surface diverges from the catalog, so the
script can be used as a regression check.

Usage:
    scripts/analyze-vhal-surface.py <path-to-vhal-binary> [path-to-dbc.json]
"""

import collections
import json
import pathlib
import re
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DBC = ROOT / "data" / "dbc.json"

# Buffer symbols are named after the CAN ID they hold: MMI_Rx_1A2 / MMI_Tx_2A5.
BUFFER_PATTERN = re.compile(r"MMI_(Rx|Tx)_([0-9A-Fa-f]{3})$")

# Per-signal accessors embed the frame ID and signal name: ILGetRx_MMI_085_EMS_EngStatus.
RX_ACCESSORS = ("ILGetRx", "ILCheckRx")
TX_ACCESSORS = ("ILPutTx", "ILGetTx", "ILRstTx")
ACCESSOR_PATTERN = re.compile(
    r"(%s)_[A-Za-z]+_([0-9A-Fa-f]{3})_(.+)"
    % "|".join(RX_ACCESSORS + TX_ACCESSORS)
)

SYMBOL_ENTRY_SIZE = 24
SECTION_HEADER_FORMAT = "<IIQQQQIIQQ"
SHT_SYMTAB = 2
SHT_DYNSYM = 11
FRAME_BITS = 64


class Elf:
    """Minimal ELF64 little-endian reader for symbol tables and named sections."""

    def __init__(self, path):
        self.data = pathlib.Path(path).read_bytes()
        if self.data[:4] != b"\x7fELF" or self.data[4] != 2:
            raise ValueError("not an ELF64 file: %s" % path)
        section_offset, = struct.unpack_from("<Q", self.data, 0x28)
        entry_size, count, string_index = struct.unpack_from("<HHH", self.data, 0x3A)
        self.sections = []
        for index in range(count):
            fields = struct.unpack_from(
                SECTION_HEADER_FORMAT, self.data, section_offset + index * entry_size
            )
            self.sections.append(
                {
                    "name_offset": fields[0],
                    "type": fields[1],
                    "offset": fields[4],
                    "size": fields[5],
                    "link": fields[6],
                }
            )
        names_offset = self.sections[string_index]["offset"]
        for section in self.sections:
            section["name"] = self._string_at(names_offset + section["name_offset"])

    def _string_at(self, offset):
        end = self.data.index(b"\0", offset)
        return self.data[offset:end].decode("utf-8", "replace")

    def section(self, name):
        for section in self.sections:
            if section["name"] == name:
                return section
        return None

    def symbols(self):
        """Yield (name, value, size) for every symbol table entry."""
        for section in self.sections:
            if section["type"] not in (SHT_SYMTAB, SHT_DYNSYM):
                continue
            strings = self.sections[section["link"]]["offset"]
            for index in range(section["size"] // SYMBOL_ENTRY_SIZE):
                offset = section["offset"] + index * SYMBOL_ENTRY_SIZE
                name_offset, _, _, _, value, size = struct.unpack_from(
                    "<IBBHQQ", self.data, offset
                )
                name = self._string_at(strings + name_offset)
                if name:
                    yield name, value, size

    def debug_strings(self):
        section = self.section(".debug_str")
        if not section:
            return set()
        blob = self.data[section["offset"] : section["offset"] + section["size"]]
        return {
            chunk.decode("utf-8", "replace") for chunk in blob.split(b"\0") if chunk
        }


def collect_buffers(elf):
    """Return {"Rx": {can_id: size}, "Tx": {can_id: size}} from buffer symbols."""
    buffers = {"Rx": {}, "Tx": {}}
    addresses = {"Rx": {}, "Tx": {}}
    for name, value, size in elf.symbols():
        match = BUFFER_PATTERN.fullmatch(name)
        if not match:
            continue
        direction, can_id = match.group(1), match.group(2).upper()
        buffers[direction][can_id] = size
        addresses[direction][can_id] = value
    return buffers, addresses


def collect_accessor_signals(elf):
    """Return {"rx": {(can_id, name)}, "tx": {(can_id, name)}} from DWARF strings."""
    signals = {"rx": set(), "tx": set()}
    for text in elf.debug_strings():
        match = ACCESSOR_PATTERN.fullmatch(text)
        if not match:
            continue
        kind = "rx" if match.group(1) in RX_ACCESSORS else "tx"
        signals[kind].add((match.group(2).upper(), match.group(3)))
    return signals


def catalog_bit_coverage(document):
    """Return (claimed_bits, total_bits, overlaps, per_frame_unclaimed)."""
    by_frame = collections.defaultdict(list)
    for signal in document["signals"]:
        by_frame[signal["canid"].upper()].append(signal)

    overlaps = 0
    claimed = 0
    unclaimed = {}
    for frame in document["frames"]:
        frame = frame.upper()
        occupied = [None] * FRAME_BITS
        for signal in by_frame.get(frame, []):
            for part in signal["parts"]:
                # "word" is a byte offset (0 or 4); "off" is a bit offset inside
                # that 32-bit little-endian word, counted from the most
                # significant bit. The pair addresses one unique bit slot.
                base = part["word"] * 8 + part["off"]
                for bit in range(part["bits"]):
                    index = base + bit
                    if index < FRAME_BITS:
                        if occupied[index] is not None:
                            overlaps += 1
                        occupied[index] = signal["name"]
        used = sum(1 for slot in occupied if slot is not None)
        claimed += used
        unclaimed[frame] = FRAME_BITS - used
    return claimed, len(document["frames"]) * FRAME_BITS, overlaps, unclaimed


def contiguous_span(addresses, size):
    """Return (start, end, gap_count) for a set of equally sized buffers."""
    if not addresses:
        return 0, 0, 0
    ordered = sorted(addresses.values())
    gaps = sum(1 for a, b in zip(ordered, ordered[1:]) if b - a != size)
    return ordered[0], ordered[-1] + size, gaps


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    binary = sys.argv[1]
    dbc_path = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_DBC

    elf = Elf(binary)
    document = json.loads(dbc_path.read_text())
    catalog_frames = {frame.upper() for frame in document["frames"]}
    catalog_signals = {
        (signal["canid"].upper(), signal["name"]) for signal in document["signals"]
    }

    buffers, addresses = collect_buffers(elf)
    accessors = collect_accessor_signals(elf)
    receive, transmit = buffers["Rx"], buffers["Tx"]

    print("== buffers ==")
    print("Rx buffer symbols       : %d" % len(receive))
    print("Tx buffer symbols       : %d" % len(transmit))
    print("catalog frames          : %d" % len(catalog_frames))
    missing_frames = sorted(set(receive) - catalog_frames)
    extra_frames = sorted(catalog_frames - set(receive))
    print("Rx buffers not in catalog: %s" % (missing_frames or "none"))
    print("catalog frames not in Rx : %s" % (extra_frames or "none"))

    sizes = set(receive.values()) | set(transmit.values())
    print("buffer st_size values    : %s" % sorted(sizes))
    extended = sorted(
        can_id for can_id in set(receive) | set(transmit) if int(can_id, 16) > 0x7FF
    )
    print("extended (>0x7FF) IDs    : %s" % (extended or "none"))

    for label, table in (("Rx", addresses["Rx"]), ("Tx", addresses["Tx"])):
        if not table:
            continue
        start, end, gaps = contiguous_span(table, 8)
        print(
            "%s block 0x%x..0x%x (%d bytes), non-adjacent gaps: %d"
            % (label, start, end, end - start, gaps)
        )
    if addresses["Rx"] and addresses["Tx"]:
        rx_end = max(addresses["Rx"].values()) + 8
        tx_start = min(addresses["Tx"].values())
        print("Rx and Tx blocks contiguous: %s" % (rx_end == tx_start))

    print()
    print("== signals defined in DWARF accessors ==")
    rx_signals, tx_signals = accessors["rx"], accessors["tx"]
    rx_missing = rx_signals - catalog_signals
    rx_missing_known_frame = {
        pair for pair in rx_missing if pair[0] in catalog_frames
    }
    print("Rx accessor signals      : %d" % len(rx_signals))
    print("  already in catalog     : %d" % len(rx_signals & catalog_signals))
    print("  absent from catalog    : %d" % len(rx_missing))
    print("    on frames we read    : %d" % len(rx_missing_known_frame))
    print("catalog signals w/o accessor: %d" % len(catalog_signals - rx_signals))

    tx_on_buffers = {pair for pair in tx_signals if pair[0] in transmit}
    print("Tx accessor signals      : %d" % len(tx_signals))
    print("  on Tx buffers          : %d across %d frames"
          % (len(tx_on_buffers), len({pair[0] for pair in tx_on_buffers})))
    print("  overlapping catalog    : %d" % len(tx_signals & catalog_signals))
    print("  Tx buffers with no signals: %s"
          % (sorted(set(transmit) - {pair[0] for pair in tx_signals}) or "none"))

    print()
    print("== catalog bit coverage of frames already read ==")
    claimed, total, overlaps, unclaimed = catalog_bit_coverage(document)
    print("total bits               : %d" % total)
    print("claimed by signals       : %d (%.1f%%)" % (claimed, 100.0 * claimed / total))
    print("unclaimed                : %d (%.1f%%)"
          % (total - claimed, 100.0 - 100.0 * claimed / total))
    print("overlapping assignments  : %d" % overlaps)
    print("frames fully mapped      : %d"
          % sum(1 for value in unclaimed.values() if value == 0))
    worst = sorted(unclaimed.items(), key=lambda item: -item[1])[:5]
    print("least mapped frames      : %s"
          % ", ".join("0x%s (%d free)" % pair for pair in worst))

    if missing_frames or extra_frames or rx_missing_known_frame:
        print()
        print("DIVERGENCE: the receive-side surface no longer matches the catalog.")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
