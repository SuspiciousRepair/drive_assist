#define _POSIX_C_SOURCE 200809L

#include "roadcast_source_session.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

#define RETRY_INITIAL_NS UINT64_C(250000000)
#define RETRY_MAX_NS UINT64_C(30000000000)

static void mark_unavailable(roadcast_frame_t frames[ROADCAST_FRAME_COUNT]) {
    memset(frames, 0, sizeof(roadcast_frame_t) * ROADCAST_FRAME_COUNT);
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        frames[i].can_id = ROADCAST_CAN_IDS[i];
        frames[i].state = ROADCAST_OBSERVATION_UNAVAILABLE;
    }
}

static void schedule_retry(roadcast_source_session_t *session, uint64_t now_ns) {
    int saved_errno = errno;
    roadcast_vhal_close(&session->vhal);
    session->connected = 0;
    session->retry_at_ns = now_ns + session->retry_delay_ns;
    if (session->retry_delay_ns < RETRY_MAX_NS / 2)
        session->retry_delay_ns *= 2;
    else
        session->retry_delay_ns = RETRY_MAX_NS;
    errno = saved_errno;
}

int roadcast_source_session_init(roadcast_source_session_t *session,
                                 const char *process_name) {
    memset(session, 0, sizeof(*session));
    session->vhal.mem_fd = -1;
    session->vhal.identity_fd = -1;
    session->retry_delay_ns = RETRY_INITIAL_NS;
    session->process_name = strdup(process_name);
    return session->process_name ? 0 : -1;
}

int roadcast_source_session_read(roadcast_source_session_t *session,
                                 roadcast_frame_t frames[ROADCAST_FRAME_COUNT],
                                 uint64_t now_ns) {
    if (!session->connected) {
        mark_unavailable(frames);
        if (now_ns < session->retry_at_ns) {
            errno = EAGAIN;
            return ROADCAST_SOURCE_WAITING;
        }
        if (roadcast_vhal_open(&session->vhal, session->process_name) < 0) {
            schedule_retry(session, now_ns);
            return -1;
        }
        session->connected = 1;
        session->generation++;
    }

    int result = roadcast_vhal_read(&session->vhal, frames);
    if (result < 0) {
        /* A failed source must never publish a partially fresh snapshot. */
        mark_unavailable(frames);
        schedule_retry(session, now_ns);
    } else {
        session->retry_delay_ns = RETRY_INITIAL_NS;
    }
    return result;
}

void roadcast_source_session_close(roadcast_source_session_t *session) {
    roadcast_vhal_close(&session->vhal);
    free(session->process_name);
    session->process_name = NULL;
    session->connected = 0;
}
