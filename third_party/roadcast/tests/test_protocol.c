#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "roadcast_protocol.h"

static void test_header_round_trip(void) {
    roadcast_header_t input = {
        .version = ROADCAST_PROTOCOL_VERSION,
        .type = ROADCAST_MSG_UPDATE_BATCH,
        .flags = 0x10203040u,
        .payload_bytes = 0x5060u,
        .sequence = 0x0102030405060708ull,
        .timestamp_ns = 0x1112131415161718ull,
    };
    const uint8_t expected[ROADCAST_HEADER_SIZE] = {
        0x52, 0x44, 0x43, 0x54, 0x00, 0x03, 0x00, 0x06, 0x10, 0x20, 0x30,
        0x40, 0x00, 0x00, 0x50, 0x60, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        0x07, 0x08, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
    };
    uint8_t encoded[ROADCAST_HEADER_SIZE];
    roadcast_encode_header(encoded, &input);
    assert(memcmp(encoded, expected, sizeof(expected)) == 0);

    roadcast_header_t output = {0};
    assert(roadcast_decode_header(encoded, &output) == 0);
    assert(output.version == input.version);
    assert(output.type == input.type);
    assert(output.flags == input.flags);
    assert(output.payload_bytes == input.payload_bytes);
    assert(output.sequence == input.sequence);
    assert(output.timestamp_ns == input.timestamp_ns);

    encoded[0] = 0;
    assert(roadcast_decode_header(encoded, &output) < 0);
}

static void test_handshake_round_trip(void) {
    uint8_t hello[8];
    assert(roadcast_encode_hello(hello) == sizeof(hello));
    uint16_t min_version;
    uint16_t max_version;
    uint32_t capabilities;
    assert(roadcast_decode_hello(hello, sizeof(hello), &min_version,
                                 &max_version, &capabilities) == 0);
    assert(min_version == ROADCAST_PROTOCOL_VERSION);
    assert(max_version == ROADCAST_PROTOCOL_VERSION);
    assert(capabilities == (ROADCAST_CAP_RAW_CAN | ROADCAST_CAP_DECODED_CAN));
    assert(roadcast_decode_hello(hello, sizeof(hello) - 1, &min_version,
                                 &max_version, &capabilities) < 0);

    uint8_t welcome[ROADCAST_WELCOME_SIZE];
    assert(roadcast_encode_welcome(
               welcome, 60, 111, 815, 4064, 2016,
               ROADCAST_CAP_RAW_CAN | ROADCAST_CAP_DECODED_CAN, 1,
               UINT64_C(0x123456789abcdef0)) == sizeof(welcome));
    uint32_t hz;
    uint32_t frame_count;
    uint32_t signal_count;
    uint32_t max_request_payload;
    uint32_t max_response_payload;
    uint32_t schema_version;
    uint64_t schema_hash;
    assert(roadcast_decode_welcome(welcome, sizeof(welcome), &hz, &frame_count,
                                   &signal_count, &max_request_payload,
                                   &max_response_payload, &capabilities,
                                   &schema_version, &schema_hash) == 0);
    assert(hz == 60);
    assert(frame_count == 111);
    assert(signal_count == 815);
    assert(max_request_payload == 4064);
    assert(max_response_payload == 2016);
    assert(capabilities == (ROADCAST_CAP_RAW_CAN | ROADCAST_CAP_DECODED_CAN));
    assert(schema_version == 1);
    assert(schema_hash == UINT64_C(0x123456789abcdef0));

    uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
    assert(roadcast_encode_index_request(request, 567) == sizeof(request));
    uint32_t start_index;
    assert(roadcast_decode_index_request(request, sizeof(request),
                                         &start_index) == 0);
    assert(start_index == 567);
}

static void test_signal_batch_round_trip(void) {
    roadcast_signal_value_t input[3] = {
        {.raw = UINT64_C(0x0102030405060708),
         .physical = 12.5,
         .first_observed_ns = 10,
         .last_change_ns = 20,
         .state = ROADCAST_OBSERVATION_VALID,
         .calibrated = 1},
        {.raw = 7,
         .physical = -3.25,
         .state = ROADCAST_OBSERVATION_NEVER_OBSERVED,
         .calibrated = 0},
        {.raw = UINT64_MAX,
         .physical = -1.0,
         .first_observed_ns = 30,
         .last_change_ns = 40,
         .state = ROADCAST_OBSERVATION_INVALID,
         .calibrated = 0},
    };
    uint32_t indices[] = {2, 0};
    uint8_t encoded[ROADCAST_BATCH_PREFIX_SIZE + 2 * ROADCAST_SIGNAL_WIRE_SIZE];
    size_t length = roadcast_encode_signal_batch(
        encoded, sizeof(encoded), 99, 3, UINT32_MAX, input, indices, 2);
    assert(length == sizeof(encoded));

    roadcast_signal_update_t output[2];
    uint64_t sample_sequence;
    uint32_t total_count;
    uint32_t start_index;
    size_t count;
    assert(roadcast_decode_signal_batch(encoded, length, &sample_sequence,
                                        &total_count, &start_index, output, 2,
                                        &count) == 0);
    assert(sample_sequence == 99);
    assert(total_count == 3);
    assert(start_index == UINT32_MAX);
    assert(count == 2);
    assert(output[0].index == 2);
    assert(output[0].value.raw == input[2].raw);
    assert(output[0].value.physical == input[2].physical);
    assert(output[0].value.state == input[2].state);
    assert(output[0].value.first_observed_ns == input[2].first_observed_ns);
    assert(output[0].value.last_change_ns == input[2].last_change_ns);
    assert(output[1].index == 0);
    assert(output[1].value.raw == input[0].raw);
    assert(output[1].value.physical == input[0].physical);

    encoded[ROADCAST_BATCH_PREFIX_SIZE + 4] = 2;
    assert(roadcast_decode_signal_batch(encoded, length, &sample_sequence,
                                        &total_count, &start_index, output, 2,
                                        &count) == 0);
    encoded[ROADCAST_BATCH_PREFIX_SIZE + 4] = 4;
    assert(roadcast_decode_signal_batch(encoded, length, &sample_sequence,
                                        &total_count, &start_index, output, 2,
                                        &count) < 0);
}

static void test_frame_batch_round_trip(void) {
    roadcast_frame_t input[3] = {
        {.can_id = 0x085,
         .state = ROADCAST_OBSERVATION_VALID,
         .data = {0, 1, 2, 3, 4, 5, 6, 7},
         .first_observed_ns = 10,
         .last_change_ns = 20},
        {.can_id = 0x123,
         .state = ROADCAST_OBSERVATION_UNAVAILABLE,
         .data = {8, 9, 10, 11, 12, 13, 14, 15}},
        {.can_id = 0x7df,
         .state = ROADCAST_OBSERVATION_NEVER_OBSERVED,
         .data = {16, 17, 18, 19, 20, 21, 22, 23},
         .first_observed_ns = 30,
         .last_change_ns = 40},
    };
    uint16_t indices[] = {2, 0};
    uint8_t encoded[ROADCAST_BATCH_PREFIX_SIZE + 2 * ROADCAST_FRAME_WIRE_SIZE];
    size_t encoded_length = roadcast_encode_frame_batch(
        encoded, sizeof(encoded), 42, 3, UINT32_MAX, input, indices, 2);
    assert(encoded_length == sizeof(encoded));

    roadcast_frame_t output[2];
    uint64_t sample_sequence;
    uint32_t total_count;
    uint32_t start_index;
    size_t frame_count;
    assert(roadcast_decode_frame_batch(
               encoded, encoded_length, &sample_sequence, &total_count,
               &start_index, output, 2, &frame_count) == 0);
    assert(sample_sequence == 42);
    assert(frame_count == 2);
    assert(total_count == 3);
    assert(start_index == UINT32_MAX);
    assert(output[0].can_id == input[2].can_id);
    assert(memcmp(output[0].data, input[2].data, sizeof(output[0].data)) == 0);
    assert(output[0].state == input[2].state);
    assert(output[0].first_observed_ns == input[2].first_observed_ns);
    assert(output[1].can_id == input[0].can_id);
    assert(memcmp(output[1].data, input[0].data, sizeof(output[1].data)) == 0);

    assert(roadcast_encode_frame_batch(encoded, sizeof(encoded) - 1, 42, 3,
                                       UINT32_MAX, input, indices, 2) == 0);
    assert(roadcast_decode_frame_batch(
               encoded, encoded_length - 1, &sample_sequence, &total_count,
               &start_index, output, 2, &frame_count) < 0);
    assert(roadcast_decode_frame_batch(
               encoded, encoded_length, &sample_sequence, &total_count,
               &start_index, output, 1, &frame_count) < 0);

    encoded[18] = 1;
    assert(roadcast_decode_frame_batch(
               encoded, encoded_length, &sample_sequence, &total_count,
               &start_index, output, 2, &frame_count) < 0);
    encoded[18] = 0;
    encoded[ROADCAST_BATCH_PREFIX_SIZE + 2] = 3;
    assert(roadcast_decode_frame_batch(
               encoded, encoded_length, &sample_sequence, &total_count,
               &start_index, output, 2, &frame_count) < 0);
}

static void test_heartbeat_round_trip(void) {
    uint8_t encoded[ROADCAST_HEARTBEAT_SIZE];
    assert(roadcast_encode_heartbeat(encoded, 1234, 99, 56, 7, 59950,
                                     ROADCAST_SOURCE_STATE_DEGRADED) ==
           sizeof(encoded));
    uint64_t sample_sequence;
    uint64_t change_sequence;
    uint64_t dropped_batches;
    uint64_t coalesced_samples;
    uint32_t effective_hz_millihz;
    uint8_t source_state;
    assert(roadcast_decode_heartbeat(encoded, sizeof(encoded), &sample_sequence,
                                     &change_sequence, &dropped_batches,
                                     &coalesced_samples, &effective_hz_millihz,
                                     &source_state) == 0);
    assert(sample_sequence == 1234);
    assert(change_sequence == 99);
    assert(dropped_batches == 56);
    assert(coalesced_samples == 7);
    assert(effective_hz_millihz == 59950);
    assert(source_state == ROADCAST_SOURCE_STATE_DEGRADED);
    assert(roadcast_decode_heartbeat(encoded, sizeof(encoded) - 1,
                                     &sample_sequence, &change_sequence,
                                     &dropped_batches, &coalesced_samples,
                                     &effective_hz_millihz, &source_state) < 0);
}

int main(void) {
    test_header_round_trip();
    test_handshake_round_trip();
    test_frame_batch_round_trip();
    test_signal_batch_round_trip();
    test_heartbeat_round_trip();
    puts("protocol tests passed");
    return 0;
}
