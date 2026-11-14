#ifndef ROADCAST_CLIENT_H
#define ROADCAST_CLIENT_H

#include <stddef.h>
#include <stdint.h>

#include "roadcast_protocol.h"

typedef struct roadcast_client roadcast_client_t;

enum roadcast_client_error {
    ROADCAST_CLIENT_OK = 0,
    ROADCAST_CLIENT_ERR_ARGUMENT = -1,
    ROADCAST_CLIENT_ERR_SOCKET = -2,
    ROADCAST_CLIENT_ERR_HANDSHAKE = -3,
    ROADCAST_CLIENT_ERR_PROTOCOL = -4,
    ROADCAST_CLIENT_ERR_SCHEMA = -5,
    ROADCAST_CLIENT_ERR_SNAPSHOT = -6,
    ROADCAST_CLIENT_ERR_MEMORY = -7,
    ROADCAST_CLIENT_ERR_THREAD = -8,
};

typedef struct {
    uint32_t hz;
    uint32_t frame_count;
    uint32_t signal_count;
    uint32_t schema_version;
    uint64_t schema_hash;
    uint64_t sample_sequence;
    uint64_t change_sequence;
    uint64_t sample_timestamp_ns;
    uint64_t dropped_batches;
    uint64_t coalesced_samples;
    uint64_t resynchronizations;
    uint32_t effective_hz_millihz;
    uint8_t source_state;
    uint8_t connected;
} roadcast_client_status_t;

/*
 * Connects, discovers the schema, retrieves a consistent snapshot, subscribes,
 * and starts the background reader. The returned cache is immediately readable.
 */
roadcast_client_t *roadcast_client_connect(const char *socket_name, int *error);

/*
 * Stops the reader and releases the socket, cache, schema, and all native
 * resources. It is safe to pass NULL.
 */
void roadcast_client_close(roadcast_client_t *client);

int roadcast_client_status(roadcast_client_t *client,
                           roadcast_client_status_t *status);

/*
 * Schema storage is immutable and owned by the client. The pointer remains
 * valid until roadcast_client_close().
 */
const roadcast_schema_entry_t *
roadcast_client_schema_at(const roadcast_client_t *client, uint32_t index);

int32_t roadcast_client_find_signal(const roadcast_client_t *client,
                                    const char *name);

int32_t
roadcast_client_find_signal_by_stable_id(const roadcast_client_t *client,
                                         uint64_t stable_id);

int roadcast_client_read_signal(roadcast_client_t *client, uint32_t index,
                                roadcast_signal_value_t *value);

int roadcast_client_read_signals(roadcast_client_t *client,
                                 const uint32_t *indices, size_t count,
                                 roadcast_signal_value_t *values);

/*
 * Copies the cached raw frame carrying can_id, including its eight source
 * bytes and observation state. Returns ROADCAST_CLIENT_ERR_ARGUMENT when the
 * catalog defines no frame for can_id.
 *
 * The bytes are the unmodified source frame: they also carry the bits the
 * catalog defines no signal for. A consumer reading those bits is reading an
 * uncalibrated value with no confirmed scale, offset, or unit.
 */
int roadcast_client_read_frame(roadcast_client_t *client, uint16_t can_id,
                               roadcast_frame_t *frame);

/*
 * Copies the complete current signal cache. Returns the number of copied
 * entries, or a negative client error.
 */
int32_t roadcast_client_read_all(roadcast_client_t *client,
                                 roadcast_signal_value_t *values,
                                 size_t capacity);

/*
 * Returns CLOCK_MONOTONIC age of the latest source sample, or -1 when no
 * current connected sample exists.
 */
int64_t roadcast_client_sample_age_ns(roadcast_client_t *client);

const char *roadcast_client_error_string(int error);

#endif
