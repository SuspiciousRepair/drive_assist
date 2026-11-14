#!/usr/bin/env python3
"""Generate the compiled Roadcast CAN catalog from data/dbc.json."""

import json
import math
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parent.parent
INPUT = ROOT / "data" / "dbc.json"
HEADER = ROOT / "include" / "roadcast_catalog_generated.h"
FRAMES_HEADER = ROOT / "include" / "roadcast_frames.h"
SOURCE = ROOT / "src" / "catalog_generated.c"
NAMESPACE = b"roadcast.can.signal.v1"
SCHEMA_PREFIX = b"roadcast.schema.can.v1\0"
FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3


def fnv1a(data, initial=FNV_OFFSET):
    value = initial
    for byte in data:
        value ^= byte
        value = (value * FNV_PRIME) & 0xFFFFFFFFFFFFFFFF
    return value


def stable_id(signal):
    canonical = (
        NAMESPACE
        + b"\0"
        + int(signal["canid"], 16).to_bytes(2, "big")
        + signal["name"].encode("utf-8")
    )
    return fnv1a(canonical)


def c_string(value):
    return json.dumps(value, ensure_ascii=True)


def schema_hash(signals, stable_ids, invalid_indices):
    value = fnv1a(SCHEMA_PREFIX)
    for index, signal in enumerate(signals):
        canonical = bytearray()
        canonical += stable_ids[index].to_bytes(8, "big")
        canonical += index.to_bytes(4, "big")
        canonical += invalid_indices[index].to_bytes(4, "big")
        canonical += int(signal["canid"], 16).to_bytes(2, "big")
        canonical += bytes(
            [
                int(signal["width"]),
                bool(signal["signed"]),
                bool(signal["calibrated"]),
                len(signal["parts"]),
            ]
        )
        canonical += struct.pack(">d", float(signal["scale"]))
        canonical += struct.pack(">d", float(signal["offset"]))
        name = signal["name"].encode("utf-8")
        unit = signal["unit"].encode("utf-8")
        canonical += len(name).to_bytes(2, "big") + name
        canonical += len(unit).to_bytes(2, "big") + unit
        for part in signal["parts"]:
            canonical += bytes([part["word"], part["off"], part["bits"]])
        value = fnv1a(canonical, value)
    return value


def main():
    document = json.loads(INPUT.read_text())
    frames = document["frames"]
    signals = document["signals"]
    if len(frames) != len(set(frames)):
        raise ValueError("duplicate CAN frame ID")
    if frames != sorted(frames):
        raise ValueError("CAN frame IDs must be sorted")
    for can_id in frames:
        if len(can_id) != 3 or int(can_id, 16) > 0x7FF:
            raise ValueError(f"invalid standard CAN frame ID: {can_id}")

    identities = {
        (signal["canid"], signal["name"]) for signal in signals
    }
    if len(identities) != len(signals):
        raise ValueError("duplicate signal identity")
    for signal in signals:
        identity = (signal["canid"], signal["name"])
        if signal["canid"] not in frames:
            raise ValueError(f"unknown frame for signal: {identity}")
        if not signal["name"] or len(signal["name"].encode("utf-8")) > 63:
            raise ValueError(f"invalid signal name length: {identity}")
        if len(signal["unit"].encode("utf-8")) > 15:
            raise ValueError(f"invalid signal unit length: {identity}")
        if not 1 <= signal["width"] <= 64:
            raise ValueError(f"invalid signal width: {identity}")
        if not signal["parts"]:
            raise ValueError(f"signal has no parts: {identity}")
        if sum(part["bits"] for part in signal["parts"]) != signal["width"]:
            raise ValueError(f"parts do not match signal width: {identity}")
        for part in signal["parts"]:
            if part["word"] not in (0, 4):
                raise ValueError(f"invalid part word: {identity}")
            if not 1 <= part["bits"] <= 32:
                raise ValueError(f"invalid part width: {identity}")
            if not 0 <= part["off"] < 32:
                raise ValueError(f"invalid part offset: {identity}")
            if part["off"] + part["bits"] > 32:
                raise ValueError(f"part exceeds 32-bit word: {identity}")
        if not math.isfinite(float(signal["scale"])) or not math.isfinite(
            float(signal["offset"])
        ):
            raise ValueError(f"non-finite conversion: {identity}")
        invalid_flag = signal.get("invalid_flag")
        if invalid_flag and (signal["canid"], invalid_flag) not in identities:
            raise ValueError(f"unknown invalid flag: {identity}")

    frame_indices = {can_id: index for index, can_id in enumerate(frames)}
    signal_indices = {
        (signal["canid"], signal["name"]): index
        for index, signal in enumerate(signals)
    }
    invalid_indices = [
        signal_indices.get(
            (signal["canid"], signal.get("invalid_flag", "")), 0xFFFFFFFF
        )
        for signal in signals
    ]
    stable_ids = [stable_id(signal) for signal in signals]
    if len(stable_ids) != len(set(stable_ids)):
        raise RuntimeError("stable ID collision in generated catalog")

    digest = schema_hash(signals, stable_ids, invalid_indices)

    header = f"""\
// Generated by scripts/generate-catalog.py. Do not edit.
#ifndef ROADCAST_CATALOG_GENERATED_H
#define ROADCAST_CATALOG_GENERATED_H

#define ROADCAST_SIGNAL_COUNT {len(signals)}u

extern const uint64_t ROADCAST_CAN_SCHEMA_HASH;
extern const roadcast_signal_definition_t
    ROADCAST_SIGNALS[ROADCAST_SIGNAL_COUNT];

#endif
"""
    HEADER.write_text(header)

    frame_values = ", ".join(f"0x{can_id}" for can_id in frames)
    frames_header = f"""\
// Generated by scripts/generate-catalog.py. Do not edit.
#ifndef ROADCAST_FRAMES_H
#define ROADCAST_FRAMES_H

#include <stdint.h>

// clang-format off
static const uint16_t ROADCAST_CAN_IDS[] = {{
    {frame_values},
}};
// clang-format on

#define ROADCAST_FRAME_COUNT \\
    (sizeof(ROADCAST_CAN_IDS) / sizeof(ROADCAST_CAN_IDS[0]))

#endif
"""
    FRAMES_HEADER.write_text(frames_header)

    lines = [
        "// Generated by scripts/generate-catalog.py. Do not edit.",
        "// clang-format off",
        '#include "roadcast_catalog.h"',
        "",
    ]
    for index, signal in enumerate(signals):
        parts = ", ".join(
            f"{{{part['word']}, {part['off']}, {part['bits']}}}"
            for part in signal["parts"]
        )
        lines.append(
            f"static const roadcast_signal_part_t SIGNAL_PARTS_{index}[] = "
            f"{{{parts}}};"
        )
    lines.extend(
        [
            "",
            f"const uint64_t ROADCAST_CAN_SCHEMA_HASH = "
            f"UINT64_C(0x{digest:016x});",
            "",
            "const roadcast_signal_definition_t",
            "    ROADCAST_SIGNALS[ROADCAST_SIGNAL_COUNT] = {",
        ]
    )
    for index, signal in enumerate(signals):
        lines.extend(
            [
                "        {",
                f"            .stable_id = UINT64_C(0x{stable_ids[index]:016x}),",
                f"            .name = {c_string(signal['name'])},",
                f"            .unit = {c_string(signal['unit'])},",
                f"            .parts = SIGNAL_PARTS_{index},",
                f"            .invalid_signal_index = "
                f"UINT32_C({invalid_indices[index]}),",
                f"            .can_id = UINT16_C(0x{signal['canid']}),",
                f"            .frame_index = UINT16_C("
                f"{frame_indices[signal['canid']]}),",
                f"            .part_count = UINT8_C({len(signal['parts'])}),",
                f"            .width = UINT8_C({signal['width']}),",
                f"            .is_signed = UINT8_C({int(signal['signed'])}),",
                f"            .calibrated = "
                f"UINT8_C({int(signal['calibrated'])}),",
                f"            .scale = {float(signal['scale']):.17g},",
                f"            .offset = {float(signal['offset']):.17g},",
                "        },",
            ]
        )
    lines.extend(["    };", "// clang-format on", ""])
    SOURCE.write_text("\n".join(lines))
    print(
        f"generated {len(frames)} frames, {len(signals)} signals, "
        f"schema hash 0x{digest:016x}"
    )


if __name__ == "__main__":
    main()
