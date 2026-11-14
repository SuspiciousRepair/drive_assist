#include "roadcast_catalog.h"

#include <limits.h>
#include <string.h>

static uint32_t read_le32(const uint8_t *input) {
    return (uint32_t)input[0] | ((uint32_t)input[1] << 8) |
           ((uint32_t)input[2] << 16) | ((uint32_t)input[3] << 24);
}

static uint32_t extract_part(const uint8_t frame[8],
                             const roadcast_signal_part_t *part) {
    uint32_t word = read_le32(frame + part->word);
    unsigned int shift = 32u - part->offset - part->bits;
    uint32_t mask =
        part->bits >= 32 ? UINT32_MAX : (UINT32_C(1) << part->bits) - 1;
    return (word >> shift) & mask;
}

uint64_t
roadcast_decode_signal_raw(const roadcast_signal_definition_t *definition,
                           const uint8_t frame[8]) {
    uint64_t value = 0;
    for (uint8_t i = 0; i < definition->part_count; i++) {
        const roadcast_signal_part_t *part = &definition->parts[i];
        value = (value << part->bits) | extract_part(frame, part);
    }
    return value;
}

double
roadcast_decode_signal_physical(const roadcast_signal_definition_t *definition,
                                uint64_t raw) {
    double numeric = (double)raw;
    if (definition->is_signed && definition->width > 0) {
        uint64_t sign = UINT64_C(1) << (definition->width - 1);
        if (raw & sign) {
            if (definition->width == 64) {
                numeric = -((double)(UINT64_MAX - raw) + 1.0);
            } else {
                uint64_t modulus = UINT64_C(1) << definition->width;
                numeric = -(double)(modulus - raw);
            }
        }
    }
    return numeric * definition->scale + definition->offset;
}

void roadcast_decode_signals(
    const roadcast_frame_t frames[ROADCAST_FRAME_COUNT],
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT]) {
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        const roadcast_signal_definition_t *definition = &ROADCAST_SIGNALS[i];
        const roadcast_frame_t *frame = &frames[definition->frame_index];
        values[i].raw = roadcast_decode_signal_raw(definition, frame->data);
        values[i].physical =
            roadcast_decode_signal_physical(definition, values[i].raw);
        values[i].state = frame->state;
        values[i].calibrated = definition->calibrated;
        values[i].first_observed_ns = frame->first_observed_ns;
        values[i].last_change_ns = frame->last_change_ns;
    }

    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        uint32_t invalid_index = ROADCAST_SIGNALS[i].invalid_signal_index;
        if (values[i].state == ROADCAST_OBSERVATION_VALID &&
            invalid_index != UINT32_MAX && values[invalid_index].raw != 0)
            values[i].state = ROADCAST_OBSERVATION_INVALID;
    }
}

static void put_u16(uint8_t *output, uint16_t value) {
    output[0] = (uint8_t)(value >> 8);
    output[1] = (uint8_t)value;
}

static void put_u32(uint8_t *output, uint32_t value) {
    output[0] = (uint8_t)(value >> 24);
    output[1] = (uint8_t)(value >> 16);
    output[2] = (uint8_t)(value >> 8);
    output[3] = (uint8_t)value;
}

static void put_u64(uint8_t *output, uint64_t value) {
    put_u32(output, (uint32_t)(value >> 32));
    put_u32(output + 4, (uint32_t)value);
}

static void put_double(uint8_t *output, double value) {
    uint64_t bits;
    _Static_assert(sizeof(bits) == sizeof(value),
                   "Roadcast requires IEEE-754 binary64 doubles");
    memcpy(&bits, &value, sizeof(bits));
    put_u64(output, bits);
}

size_t roadcast_encode_schema_chunk(uint8_t *out, size_t capacity,
                                    uint32_t start_index,
                                    uint32_t *encoded_count) {
    if (capacity < ROADCAST_SCHEMA_CHUNK_PREFIX_SIZE ||
        start_index >= ROADCAST_SIGNAL_COUNT) {
        *encoded_count = 0;
        return 0;
    }

    put_u32(out, ROADCAST_SCHEMA_VERSION);
    put_u32(out + 4, ROADCAST_SIGNAL_COUNT);
    put_u32(out + 8, start_index);
    put_u32(out + 12, 0);
    put_u64(out + 16, ROADCAST_CAN_SCHEMA_HASH);

    size_t length = ROADCAST_SCHEMA_CHUNK_PREFIX_SIZE;
    uint32_t count = 0;
    for (uint32_t i = start_index; i < ROADCAST_SIGNAL_COUNT; i++) {
        const roadcast_signal_definition_t *definition = &ROADCAST_SIGNALS[i];
        size_t name_length = strlen(definition->name);
        size_t unit_length = strlen(definition->unit);
        size_t entry_length =
            ROADCAST_SCHEMA_ENTRY_FIXED_SIZE + name_length + unit_length;
        if (entry_length > capacity - length)
            break;

        uint8_t *entry = out + length;
        put_u64(entry, definition->stable_id);
        put_u32(entry + 8, i);
        put_u32(entry + 12, definition->invalid_signal_index);
        put_u16(entry + 16, definition->can_id);
        entry[18] = ROADCAST_ITEM_CAN_SIGNAL;
        entry[19] = ROADCAST_SOURCE_RAW_CAN;
        entry[20] = definition->width;
        entry[21] =
            (definition->is_signed ? ROADCAST_SCHEMA_FLAG_SIGNED : 0) |
            (definition->calibrated ? ROADCAST_SCHEMA_FLAG_CALIBRATED : 0);
        put_u16(entry + 22, (uint16_t)name_length);
        put_u16(entry + 24, (uint16_t)unit_length);
        put_u16(entry + 26, 0);
        put_double(entry + 28, definition->scale);
        put_double(entry + 36, definition->offset);
        memcpy(entry + ROADCAST_SCHEMA_ENTRY_FIXED_SIZE, definition->name,
               name_length);
        memcpy(entry + ROADCAST_SCHEMA_ENTRY_FIXED_SIZE + name_length,
               definition->unit, unit_length);
        length += entry_length;
        count++;
    }

    put_u32(out + 12, count);
    *encoded_count = count;
    return count ? length : 0;
}
