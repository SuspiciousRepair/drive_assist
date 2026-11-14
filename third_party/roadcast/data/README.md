# CAN Catalog Source

`dbc.json` is the checked-in source for Roadcast's initial raw CAN catalog. It
contains bit layouts extracted from DWARF in the OEM VHAL binary and a small
calibration overlay.

The catalog was validated on 2026-07-26:

```text
frames: 111
signals: 815
```

Run the deterministic generator after changing the input:

```bash
make generate
```

Generated outputs:

- `include/roadcast_frames.h`
- `include/roadcast_catalog_generated.h`
- `src/catalog_generated.c`

Do not edit generated outputs directly. A catalog change must update
`ROADCAST_CAN_SCHEMA_HASH`. Stable signal IDs remain unchanged when the
semantic identity `(namespace, CAN ID, signal name)` remains unchanged.

## Coverage

`dbc.json` covers every receive buffer and every receive-side signal the
analyzed VHAL binary defines. It does not cover the 44 transmit buffers, and it
leaves 61 percent of the bits in the frames it does read unmapped because the
binary defines no signals for them.

See [Catalog surface](../docs/CATALOG_SURFACE.md) for the evidence, and re-run
`scripts/analyze-vhal-surface.py` after any VHAL update to confirm the catalog
still matches the binary.
