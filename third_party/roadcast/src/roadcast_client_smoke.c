#define _POSIX_C_SOURCE 200809L

#include "roadcast_client.h"

#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static int parse_seconds(const char *text, unsigned int *seconds) {
    char *end = NULL;
    errno = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (errno || !end || *end || value == 0 || value > 60)
        return -1;
    *seconds = (unsigned int)value;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 4) {
        fprintf(stderr, "usage: %s SOCKET [SIGNAL] [SECONDS]\n", argv[0]);
        return 2;
    }
    const char *signal_name = argc >= 3 ? argv[2] : "ESC_VehicleSpeed";
    unsigned int seconds = 2;
    if (argc == 4 && parse_seconds(argv[3], &seconds) < 0) {
        fprintf(stderr, "invalid duration: %s\n", argv[3]);
        return 2;
    }

    int error = ROADCAST_CLIENT_OK;
    roadcast_client_t *client = roadcast_client_connect(argv[1], &error);
    if (!client) {
        fprintf(stderr, "client connect failed: %s\n",
                roadcast_client_error_string(error));
        return 1;
    }

    roadcast_client_status_t status;
    if (roadcast_client_status(client, &status) < 0) {
        fprintf(stderr, "client status failed\n");
        roadcast_client_close(client);
        return 1;
    }
    printf("client-sdk: protocol=%u hz=%" PRIu32 " frames=%" PRIu32
           " signals=%" PRIu32 " schema=%" PRIu32 "\n",
           ROADCAST_PROTOCOL_VERSION, status.hz, status.frame_count,
           status.signal_count, status.schema_version);

    int32_t index = roadcast_client_find_signal(client, signal_name);
    if (index < 0) {
        fprintf(stderr, "signal not found: %s\n", signal_name);
        roadcast_client_close(client);
        return 1;
    }
    const roadcast_schema_entry_t *entry =
        roadcast_client_schema_at(client, (uint32_t)index);
    if (!entry || roadcast_client_find_signal_by_stable_id(
                      client, entry->stable_id) != index) {
        fprintf(stderr, "stable signal lookup failed: %s\n", signal_name);
        roadcast_client_close(client);
        return 1;
    }

    struct timespec delay = {.tv_sec = seconds, .tv_nsec = 0};
    while (nanosleep(&delay, &delay) < 0 && errno == EINTR)
        ;

    roadcast_signal_value_t value;
    int64_t age_ns = roadcast_client_sample_age_ns(client);
    if (roadcast_client_read_signal(client, (uint32_t)index, &value) < 0 ||
        roadcast_client_status(client, &status) < 0 || !status.connected ||
        age_ns < 0) {
        fprintf(stderr, "client cache is unavailable\n");
        roadcast_client_close(client);
        return 1;
    }
    printf("client-value: name=%s index=%" PRId32 " state=%u raw=%" PRIu64
           " physical=%.6f calibrated=%u\n",
           signal_name, index, value.state, value.raw, value.physical,
           value.calibrated);
    roadcast_frame_t frame;
    if (roadcast_client_read_frame(client, entry->can_id, &frame) < 0 ||
        frame.can_id != entry->can_id) {
        fprintf(stderr, "raw frame 0x%03x is unavailable\n", entry->can_id);
        roadcast_client_close(client);
        return 1;
    }
    if (roadcast_client_read_frame(client, 0xffff, &frame) !=
        ROADCAST_CLIENT_ERR_ARGUMENT) {
        fprintf(stderr, "unknown CAN id was accepted\n");
        roadcast_client_close(client);
        return 1;
    }
    if (roadcast_client_read_frame(client, entry->can_id, &frame) < 0) {
        fprintf(stderr, "raw frame reread failed\n");
        roadcast_client_close(client);
        return 1;
    }
    printf("client-frame: can=0x%03x state=%u data=%02x%02x%02x%02x%02x%02x"
           "%02x%02x\n",
           frame.can_id, frame.state, frame.data[0], frame.data[1],
           frame.data[2], frame.data[3], frame.data[4], frame.data[5],
           frame.data[6], frame.data[7]);

    printf("client-summary: connected=%u sample_seq=%" PRIu64
           " change_seq=%" PRIu64 " resyncs=%" PRIu64 " dropped=%" PRIu64
           " coalesced=%" PRIu64 " age_ns=%" PRId64 "\n",
           status.connected, status.sample_sequence, status.change_sequence,
           status.resynchronizations, status.dropped_batches,
           status.coalesced_samples, age_ns);

    roadcast_client_close(client);
    return 0;
}
