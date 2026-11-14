#define _POSIX_C_SOURCE 200809L
#include "roadcast_client.h"

#include <stdio.h>
#include <time.h>

int main(int argc, char **argv) {
    if (argc != 2)
        return 2;
    int error;
    roadcast_client_t *client = roadcast_client_connect(argv[1], &error);
    if (!client) {
        fprintf(stderr, "recovery client failed to connect: %d\n", error);
        return 1;
    }
    unsigned int phase = 0;
    for (unsigned int tick = 0; tick < 200 && phase < 4; tick++) {
        roadcast_client_status_t status;
        roadcast_frame_t frame;
        if (roadcast_client_status(client, &status) < 0 ||
            roadcast_client_read_frame(client, 0x085, &frame) < 0 ||
            !status.connected || status.resynchronizations ||
            status.dropped_batches) {
            fprintf(stderr, "recovery interrupted the client session\n");
            roadcast_client_close(client);
            return 1;
        }
        int want_available = phase % 2;
        if (status.source_state == (want_available
                                       ? ROADCAST_SOURCE_STATE_AVAILABLE
                                       : ROADCAST_SOURCE_STATE_UNAVAILABLE) &&
            frame.state == (want_available ? ROADCAST_OBSERVATION_VALID
                                           : ROADCAST_OBSERVATION_UNAVAILABLE)) {
            printf("phase=%u source=%s\n", phase,
                   want_available ? "available" : "unavailable");
            fflush(stdout);
            phase++;
        }
        struct timespec delay = {.tv_sec = 0, .tv_nsec = 100000000};
        nanosleep(&delay, NULL);
    }
    roadcast_client_close(client);
    if (phase != 4) {
        fprintf(stderr, "source recovery timed out at phase %u\n", phase);
        return 1;
    }
    puts("source recovery client passed");
    return 0;
}
