#ifndef ROADCAST_PROTOCOL_H
#define ROADCAST_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#define ROADCAST_MAGIC 0x52444354u
#define ROADCAST_PROTOCOL_VERSION 3u
#define ROADCAST_HEADER_SIZE 32u
#define ROADCAST_FRAME_WIRE_SIZE 28u
#define ROADCAST_BATCH_PREFIX_SIZE 20u
#define ROADCAST_SIGNAL_WIRE_SIZE 40u
#define ROADCAST_WELCOME_SIZE 36u
#define ROADCAST_HEARTBEAT_SIZE 40u
#define ROADCAST_SCHEMA_REQUEST_SIZE 4u
#define ROADCAST_SCHEMA_CHUNK_PREFIX_SIZE 24u
#define ROADCAST_SCHEMA_ENTRY_FIXED_SIZE 44u
#define ROADCAST_SCHEMA_NAME_MAX 63u
#define ROADCAST_SCHEMA_UNIT_MAX 15u
#define ROADCAST_MAX_PAYLOAD 65536u
#define ROADCAST_MAX_MESSAGE (ROADCAST_HEADER_SIZE + ROADCAST_MAX_PAYLOAD)

enum roadcast_message_type {
    ROADCAST_MSG_HELLO = 1,
    ROADCAST_MSG_WELCOME = 2,
    ROADCAST_MSG_GET_SNAPSHOT = 3,
    ROADCAST_MSG_SNAPSHOT = 4,
    ROADCAST_MSG_SUBSCRIBE_ALL = 5,
    ROADCAST_MSG_UPDATE_BATCH = 6,
    ROADCAST_MSG_HEARTBEAT = 7,
    ROADCAST_MSG_RESYNC_REQUIRED = 8,
    ROADCAST_MSG_SUBSCRIBED = 9,
    ROADCAST_MSG_PING = 10,
    ROADCAST_MSG_PONG = 11,
    ROADCAST_MSG_GET_SCHEMA = 12,
    ROADCAST_MSG_SCHEMA_CHUNK = 13,
    ROADCAST_MSG_GET_SIGNAL_SNAPSHOT = 14,
    ROADCAST_MSG_SIGNAL_SNAPSHOT_CHUNK = 15,
    ROADCAST_MSG_SIGNAL_UPDATE_BATCH = 16,
};

enum roadcast_capability {
    ROADCAST_CAP_RAW_CAN = 1u << 0,
    ROADCAST_CAP_DECODED_CAN = 1u << 1,
};

enum roadcast_item_kind {
    ROADCAST_ITEM_CAN_FRAME = 1,
    ROADCAST_ITEM_CAN_SIGNAL = 2,
    ROADCAST_ITEM_VHAL_PROPERTY = 3,
};

enum roadcast_source_kind {
    ROADCAST_SOURCE_RAW_CAN = 1,
    ROADCAST_SOURCE_VHAL_STORE = 2,
};

enum roadcast_schema_flags {
    ROADCAST_SCHEMA_FLAG_SIGNED = 1u << 0,
    ROADCAST_SCHEMA_FLAG_CALIBRATED = 1u << 1,
};

enum roadcast_observation_state {
    ROADCAST_OBSERVATION_UNAVAILABLE = 0,
    ROADCAST_OBSERVATION_NEVER_OBSERVED = 1,
    ROADCAST_OBSERVATION_VALID = 2,
    ROADCAST_OBSERVATION_INVALID = 3,
};

enum roadcast_source_state {
    ROADCAST_SOURCE_STATE_UNAVAILABLE = 0,
    ROADCAST_SOURCE_STATE_AVAILABLE = 1,
    ROADCAST_SOURCE_STATE_DEGRADED = 2,
};

typedef struct {
    uint16_t can_id;
    uint8_t state;
    uint8_t data[8];
    uint64_t first_observed_ns;
    uint64_t last_change_ns;
} roadcast_frame_t;

typedef struct {
    uint16_t version;
    uint16_t type;
    uint32_t flags;
    uint32_t payload_bytes;
    uint64_t sequence;
    uint64_t timestamp_ns;
} roadcast_header_t;

typedef struct {
    uint64_t raw;
    double physical;
    uint64_t first_observed_ns;
    uint64_t last_change_ns;
    uint8_t state;
    uint8_t calibrated;
} roadcast_signal_value_t;

typedef struct {
    uint32_t index;
    roadcast_signal_value_t value;
} roadcast_signal_update_t;

typedef struct {
    uint64_t stable_id;
    uint32_t index;
    uint32_t invalid_signal_index;
    uint16_t can_id;
    uint8_t kind;
    uint8_t source;
    uint8_t width;
    uint8_t flags;
    double scale;
    double offset;
    char name[ROADCAST_SCHEMA_NAME_MAX + 1];
    char unit[ROADCAST_SCHEMA_UNIT_MAX + 1];
} roadcast_schema_entry_t;

void roadcast_encode_header(uint8_t out[ROADCAST_HEADER_SIZE],
                            const roadcast_header_t *header);
int roadcast_decode_header(const uint8_t in[ROADCAST_HEADER_SIZE],
                           roadcast_header_t *header);

size_t roadcast_encode_hello(uint8_t out[8]);
int roadcast_decode_hello(const uint8_t *payload, size_t len,
                          uint16_t *min_version, uint16_t *max_version,
                          uint32_t *capabilities);

size_t roadcast_encode_welcome(uint8_t out[ROADCAST_WELCOME_SIZE], uint32_t hz,
                               uint32_t frame_count, uint32_t signal_count,
                               uint32_t max_request_payload,
                               uint32_t max_response_payload,
                               uint32_t capabilities, uint32_t schema_version,
                               uint64_t schema_hash);
int roadcast_decode_welcome(const uint8_t *payload, size_t len, uint32_t *hz,
                            uint32_t *frame_count, uint32_t *signal_count,
                            uint32_t *max_request_payload,
                            uint32_t *max_response_payload,
                            uint32_t *capabilities, uint32_t *schema_version,
                            uint64_t *schema_hash);

size_t roadcast_encode_index_request(uint8_t out[ROADCAST_SCHEMA_REQUEST_SIZE],
                                     uint32_t start_index);
int roadcast_decode_index_request(const uint8_t *payload, size_t len,
                                  uint32_t *start_index);

int roadcast_decode_schema_chunk(const uint8_t *payload, size_t len,
                                 uint32_t *schema_version,
                                 uint32_t *total_count, uint32_t *start_index,
                                 uint64_t *schema_hash,
                                 roadcast_schema_entry_t *entries,
                                 size_t entry_capacity, size_t *entry_count);

size_t roadcast_encode_frame_batch(uint8_t *out, size_t cap,
                                   uint64_t sample_sequence,
                                   uint32_t total_count, uint32_t start_index,
                                   const roadcast_frame_t *frames,
                                   const uint16_t *indices, size_t count);
int roadcast_decode_frame_batch(const uint8_t *payload, size_t len,
                                uint64_t *sample_sequence,
                                uint32_t *total_count, uint32_t *start_index,
                                roadcast_frame_t *frames, size_t frame_cap,
                                size_t *frame_count);

size_t roadcast_encode_signal_batch(uint8_t *out, size_t capacity,
                                    uint64_t sample_sequence,
                                    uint32_t total_count, uint32_t start_index,
                                    const roadcast_signal_value_t *signals,
                                    const uint32_t *indices, size_t count);
int roadcast_decode_signal_batch(const uint8_t *payload, size_t len,
                                 uint64_t *sample_sequence,
                                 uint32_t *total_count, uint32_t *start_index,
                                 roadcast_signal_update_t *updates,
                                 size_t update_capacity, size_t *update_count);

size_t
roadcast_encode_heartbeat(uint8_t out[ROADCAST_HEARTBEAT_SIZE],
                          uint64_t sample_sequence, uint64_t change_sequence,
                          uint64_t dropped_batches, uint64_t coalesced_samples,
                          uint32_t effective_hz_millihz, uint8_t source_state);
int roadcast_decode_heartbeat(const uint8_t *payload, size_t len,
                              uint64_t *sample_sequence,
                              uint64_t *change_sequence,
                              uint64_t *dropped_batches,
                              uint64_t *coalesced_samples,
                              uint32_t *effective_hz_millihz,
                              uint8_t *source_state);

#endif
