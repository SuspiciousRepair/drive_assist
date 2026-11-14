#ifndef ROADCAST_CATALOG_H
#define ROADCAST_CATALOG_H

#include <stddef.h>
#include <stdint.h>

#include "roadcast_frames.h"
#include "roadcast_protocol.h"

#define ROADCAST_SCHEMA_VERSION 1u
#define ROADCAST_STABLE_ID_NAMESPACE "roadcast.can.signal.v1"

typedef struct {
    uint8_t word;
    uint8_t offset;
    uint8_t bits;
} roadcast_signal_part_t;

typedef struct {
    uint64_t stable_id;
    const char *name;
    const char *unit;
    const roadcast_signal_part_t *parts;
    uint32_t invalid_signal_index;
    uint16_t can_id;
    uint16_t frame_index;
    uint8_t part_count;
    uint8_t width;
    uint8_t is_signed;
    uint8_t calibrated;
    double scale;
    double offset;
} roadcast_signal_definition_t;

#include "roadcast_catalog_generated.h"

void roadcast_decode_signals(
    const roadcast_frame_t frames[ROADCAST_FRAME_COUNT],
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT]);
uint64_t
roadcast_decode_signal_raw(const roadcast_signal_definition_t *definition,
                           const uint8_t frame[8]);
double
roadcast_decode_signal_physical(const roadcast_signal_definition_t *definition,
                                uint64_t raw);

size_t roadcast_encode_schema_chunk(uint8_t *out, size_t capacity,
                                    uint32_t start_index,
                                    uint32_t *encoded_count);

#endif
