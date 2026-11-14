#ifndef ROADCAST_SOURCE_SESSION_H
#define ROADCAST_SOURCE_SESSION_H

#include "roadcast_vhal.h"

enum { ROADCAST_SOURCE_WAITING = -2 };

typedef struct {
    roadcast_vhal_source_t vhal;
    char *process_name;
    uint64_t retry_at_ns;
    uint64_t retry_delay_ns;
    uint64_t generation;
    int connected;
} roadcast_source_session_t;

int roadcast_source_session_init(roadcast_source_session_t *session,
                                 const char *process_name);
/* Same read results as vhal_read, or WAITING when no I/O was attempted. */
int roadcast_source_session_read(roadcast_source_session_t *session,
                                 roadcast_frame_t frames[ROADCAST_FRAME_COUNT],
                                 uint64_t now_ns);
void roadcast_source_session_close(roadcast_source_session_t *session);

#endif
