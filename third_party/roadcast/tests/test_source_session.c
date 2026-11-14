/* Inject source failures without touching a vehicle process. */
#define roadcast_vhal_open mock_vhal_open
#define roadcast_vhal_read mock_vhal_read
#define roadcast_vhal_close mock_vhal_close
#include "../src/source_session.c"

#include <assert.h>
#include <stdio.h>

static unsigned int opens, reads, closes;
static int open_error, read_error, fallback;

int mock_vhal_open(roadcast_vhal_source_t *source, const char *name) {
    assert(strcmp(name, "test-vhal") == 0);
    opens++;
    if (open_error) {
        errno = open_error;
        return -1;
    }
    source->pid = (pid_t)(100 + opens);
    return 0;
}

int mock_vhal_read(roadcast_vhal_source_t *source,
                   roadcast_frame_t frames[ROADCAST_FRAME_COUNT]) {
    assert(source->pid > 0);
    reads++;
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        frames[i].can_id = ROADCAST_CAN_IDS[i];
        frames[i].state = ROADCAST_OBSERVATION_VALID;
        memset(frames[i].data, 17, sizeof(frames[i].data));
    }
    if (read_error) {
        errno = read_error;
        return -1;
    }
    return fallback;
}

void mock_vhal_close(roadcast_vhal_source_t *source) {
    closes++;
    memset(source, 0, sizeof(*source));
    source->mem_fd = -1;
    source->identity_fd = -1;
}

static void assert_unavailable(const roadcast_frame_t *frames) {
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        assert(frames[i].can_id == ROADCAST_CAN_IDS[i]);
        assert(frames[i].state == ROADCAST_OBSERVATION_UNAVAILABLE);
        for (size_t j = 0; j < sizeof(frames[i].data); j++)
            assert(frames[i].data[j] == 0);
    }
}

int main(void) {
    roadcast_source_session_t session;
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    assert(roadcast_source_session_init(&session, "test-vhal") == 0);
    open_error = ESRCH;
    assert(roadcast_source_session_read(&session, frames, 0) == -1);
    assert(errno == ESRCH && opens == 1 && reads == 0);
    assert_unavailable(frames);
    uint64_t now = 0;
    uint64_t expected_delay = RETRY_INITIAL_NS;
    /* Waiting ticks never scan /proc or read memory; backoff is capped. */
    for (unsigned int i = 0; i < 12; i++) {
        assert(session.retry_at_ns == now + expected_delay);
        unsigned int previous_opens = opens, previous_closes = closes;
        assert(roadcast_source_session_read(&session, frames,
                                             session.retry_at_ns - 1) ==
               ROADCAST_SOURCE_WAITING);
        assert(opens == previous_opens && reads == 0 &&
               closes == previous_closes);
        assert_unavailable(frames);
        now = session.retry_at_ns;
        assert(roadcast_source_session_read(&session, frames, now) == -1);
        assert(opens == previous_opens + 1);
        expected_delay = expected_delay < RETRY_MAX_NS / 2
                             ? expected_delay * 2
                             : RETRY_MAX_NS;
    }
    open_error = 0;
    now = session.retry_at_ns;
    assert(roadcast_source_session_read(&session, frames, now) == 0);
    assert(session.connected && session.generation == 1 && reads == 1);
    assert(session.retry_delay_ns == RETRY_INITIAL_NS);
    pid_t previous_pid = session.vhal.pid;

    /* Partial writes by a failing backend are discarded together. */
    read_error = ESRCH;
    assert(roadcast_source_session_read(&session, frames, ++now) == -1);
    assert(!session.connected && errno == ESRCH);
    assert_unavailable(frames);
    read_error = 0;
    fallback = 1;
    now = session.retry_at_ns;
    assert(roadcast_source_session_read(&session, frames, now) == 1);
    assert(session.vhal.pid != previous_pid && session.generation == 2);
    unsigned int previous_opens = opens;
    assert(roadcast_source_session_read(&session, frames, ++now) == 1);
    assert(opens == previous_opens); /* Successful fallback stays connected. */

    /* Opening alone must not reset backoff when every first read fails. */
    read_error = EACCES;
    assert(roadcast_source_session_read(&session, frames, ++now) == -1);
    assert(session.retry_delay_ns == RETRY_INITIAL_NS * 2);
    now = session.retry_at_ns;
    assert(roadcast_source_session_read(&session, frames, now) == -1);
    assert(session.retry_delay_ns == RETRY_INITIAL_NS * 4);
    assert_unavailable(frames);
    roadcast_source_session_close(&session);
    assert(!session.process_name && !session.connected);
    puts("source session tests passed");
    return 0;
}
