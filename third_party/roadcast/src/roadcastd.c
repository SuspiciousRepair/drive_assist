#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <uv.h>

#include "roadcast_catalog.h"
#include "roadcast_frames.h"
#include "roadcast_protocol.h"
#include "roadcast_vhal.h"
#include "roadcast_source_session.h"

#define DEFAULT_SOCKET "@roadcast"
#define DEFAULT_PROCESS "vehicle@2.0-service"
#define DEFAULT_HZ 60
#define MAX_CLIENTS 16
#define MAX_CLIENTS_PER_UID 8
#define CLIENT_INPUT_CAP 4096
#define CLIENT_MESSAGE_CAP 2048
#define CLIENT_SOCKET_SEND_BUFFER 16384
#define HEARTBEAT_INTERVAL_MS 1000
#define STATS_INTERVAL_MS 10000
#define HELLO_TIMEOUT_NS 2000000000ull
#define SETUP_TIMEOUT_NS 10000000000ull
#define SIGNALS_PER_MESSAGE                                                    \
    ((CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE -                              \
      ROADCAST_BATCH_PREFIX_SIZE) /                                            \
     ROADCAST_SIGNAL_WIRE_SIZE)
#define FRAMES_PER_MESSAGE                                                     \
    ((CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE -                              \
      ROADCAST_BATCH_PREFIX_SIZE) /                                            \
     ROADCAST_FRAME_WIRE_SIZE)
#define FRAME_BATCH_CHUNKS                                                     \
    ((ROADCAST_FRAME_COUNT + FRAMES_PER_MESSAGE - 1) / FRAMES_PER_MESSAGE)
#define SIGNAL_BATCH_CHUNKS                                                    \
    ((ROADCAST_SIGNAL_COUNT + SIGNALS_PER_MESSAGE - 1) / SIGNALS_PER_MESSAGE)
/* One complete dense update plus one heartbeat, even before write callbacks. */
#define CLIENT_QUEUE_SLOTS (FRAME_BATCH_CHUNKS + SIGNAL_BATCH_CHUNKS + 1)

typedef struct roadcast_server roadcast_server_t;
typedef struct roadcast_client roadcast_client_t;

typedef struct {
    uv_write_t request;
    roadcast_client_t *client;
    size_t length;
    int in_use;
    uint8_t data[CLIENT_MESSAGE_CAP];
} output_slot_t;

typedef struct {
    pthread_t thread;
    pthread_mutex_t published_lock;
    atomic_bool stop_requested;
    atomic_uint_fast64_t source_errors;
    atomic_uint_fast64_t fallback_batches;
    atomic_uchar source_state;
    uv_async_t *notification;
    roadcast_source_session_t source;
    roadcast_frame_t working[ROADCAST_FRAME_COUNT];
    roadcast_frame_t published[ROADCAST_FRAME_COUNT];
    uint64_t published_sequence;
    uint64_t published_timestamp_ns;
    uint64_t period_ns;
    int fake;
    int thread_started;
} sampler_t;

struct roadcast_client {
    uv_pipe_t stream;
    roadcast_server_t *server;
    int active;
    int closing;
    int greeted;
    int subscribed;
    int needs_resync;
    int resync_queued;
    uint8_t input[CLIENT_INPUT_CAP];
    size_t input_length;
    output_slot_t output[CLIENT_QUEUE_SLOTS];
    size_t pending_bytes;
    uint64_t dropped_batches;
    uint64_t hello_deadline_ns;
    uint64_t setup_deadline_ns;
    uid_t peer_uid;
    pid_t peer_pid;
    roadcast_frame_t snapshot_frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t snapshot_signals[ROADCAST_SIGNAL_COUNT];
    uint64_t snapshot_sample_sequence;
    uint64_t snapshot_change_sequence;
    uint64_t snapshot_timestamp_ns;
    uint32_t snapshot_next_frame_index;
    uint32_t snapshot_next_index;
    uint16_t catchup_frame_indices[ROADCAST_FRAME_COUNT];
    uint32_t catchup_signal_indices[ROADCAST_SIGNAL_COUNT];
    size_t catchup_frame_count;
    size_t catchup_frame_offset;
    size_t catchup_signal_count;
    size_t catchup_signal_offset;
    uint64_t catchup_sample_sequence;
    uint64_t catchup_change_sequence;
    uint64_t catchup_timestamp_ns;
    int snapshot_active;
    int snapshot_frames_complete;
    int snapshot_complete;
    int catchup_active;
    int catchup_waiting_for_capacity;
    int catchup_resumption_reported;
};

typedef struct {
    uint64_t ticks;
    uint64_t changed_frames;
    uint64_t coalesced_samples;
    uint64_t queue_drops;
    uint64_t source_errors_at_start;
    uint64_t fallback_batches_at_start;
    uint64_t hello_timeouts;
    uint64_t setup_timeouts;
    uint64_t peer_limit_rejections;
    uint64_t serialized_chunks;
} stats_t;

struct roadcast_server {
    uv_loop_t loop;
    uv_pipe_t listener;
    uv_async_t sample_notification;
    uv_timer_t heartbeat_timer;
    uv_timer_t stats_timer;
    uv_signal_t sigint_handle;
    uv_signal_t sigterm_handle;
    roadcast_client_t clients[MAX_CLIENTS];
    sampler_t sampler;
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t signals[ROADCAST_SIGNAL_COUNT];
    uint64_t sample_sequence;
    uint64_t change_sequence;
    uint64_t sample_timestamp_ns;
    uint64_t coalesced_samples;
    uint64_t heartbeat_previous_sequence;
    uint64_t heartbeat_previous_ns;
    uint32_t effective_hz_millihz;
    uint32_t hz;
    uint32_t client_queue_slots;
    stats_t stats;
    char socket_path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    int listener_initialized;
    int async_initialized;
    int heartbeat_initialized;
    int stats_initialized;
    int sigint_initialized;
    int sigterm_initialized;
    int shutting_down;
};

typedef struct {
    uv_pipe_t stream;
} rejected_client_t;

static uint64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000000000ull + (uint64_t)now.tv_nsec;
}

static void sleep_until(uint64_t deadline_ns) {
    for (;;) {
        uint64_t now = monotonic_ns();
        if (now >= deadline_ns)
            return;
        uint64_t remaining = deadline_ns - now;
        struct timespec delay = {
            .tv_sec = (time_t)(remaining / 1000000000ull),
            .tv_nsec = (long)(remaining % 1000000000ull),
        };
        if (nanosleep(&delay, NULL) == 0 || errno != EINTR)
            return;
    }
}

static socklen_t fill_unix_address(struct sockaddr_un *address,
                                   const char *name, char *filesystem_path,
                                   size_t filesystem_path_capacity) {
    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    size_t length = strlen(name);
    if (!length) {
        errno = EINVAL;
        return 0;
    }

    if (name[0] == '@') {
#if defined(__linux__) || defined(__ANDROID__)
        if (length >= sizeof(address->sun_path)) {
            errno = ENAMETOOLONG;
            return 0;
        }
        address->sun_path[0] = '\0';
        memcpy(address->sun_path + 1, name + 1, length - 1);
        return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + length);
#else
        (void)filesystem_path;
        (void)filesystem_path_capacity;
        errno = EAFNOSUPPORT;
        return 0;
#endif
    }

    if (length >= sizeof(address->sun_path) ||
        length >= filesystem_path_capacity) {
        errno = ENAMETOOLONG;
        return 0;
    }
    memcpy(address->sun_path, name, length + 1);
    memcpy(filesystem_path, name, length + 1);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + length + 1);
}

static int set_nonblocking_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0)
        return -1;
    int descriptor_flags = fcntl(fd, F_GETFD, 0);
    if (descriptor_flags < 0 ||
        fcntl(fd, F_SETFD, descriptor_flags | FD_CLOEXEC) < 0)
        return -1;
    return 0;
}

static int open_listener(const char *socket_name, char *filesystem_path,
                         size_t filesystem_path_capacity) {
    struct sockaddr_un address;
    socklen_t address_length = fill_unix_address(
        &address, socket_name, filesystem_path, filesystem_path_capacity);
    if (!address_length)
        return -1;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    if (set_nonblocking_cloexec(fd) < 0) {
        close(fd);
        return -1;
    }

    if (filesystem_path[0])
        unlink(filesystem_path);
    if (bind(fd, (const struct sockaddr *)&address, address_length) < 0 ||
        listen(fd, MAX_CLIENTS) < 0) {
        int saved_errno = errno;
        close(fd);
        if (filesystem_path[0])
            unlink(filesystem_path);
        errno = saved_errno;
        return -1;
    }
    return fd;
}

static void
initialize_fake_frames(roadcast_frame_t frames[ROADCAST_FRAME_COUNT]) {
    memset(frames, 0, sizeof(roadcast_frame_t) * ROADCAST_FRAME_COUNT);
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        frames[i].can_id = ROADCAST_CAN_IDS[i];
        frames[i].state = ROADCAST_OBSERVATION_NEVER_OBSERVED;
    }
}

static void update_fake_frames(roadcast_frame_t frames[ROADCAST_FRAME_COUNT],
                               uint64_t sequence, int dense) {
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        uint64_t value = dense ? ((sequence & 1) ? UINT64_MAX : 0)
                               : sequence + i;
        memcpy(frames[i].data, &value, sizeof(value));
    }
}

static void
merge_sampled_frames(roadcast_frame_t destination[ROADCAST_FRAME_COUNT],
                     const roadcast_frame_t sampled[ROADCAST_FRAME_COUNT],
                     uint64_t timestamp_ns, int initialize) {
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        if (initialize) {
            destination[i] = sampled[i];
            destination[i].first_observed_ns = 0;
            destination[i].last_change_ns = 0;
            continue;
        }
        if (sampled[i].state == ROADCAST_OBSERVATION_UNAVAILABLE) {
            destination[i].state = ROADCAST_OBSERVATION_UNAVAILABLE;
            continue;
        }
        if (memcmp(destination[i].data, sampled[i].data,
                   sizeof(destination[i].data)) != 0) {
            memcpy(destination[i].data, sampled[i].data,
                   sizeof(destination[i].data));
            if (!destination[i].first_observed_ns)
                destination[i].first_observed_ns = timestamp_ns;
            destination[i].last_change_ns = timestamp_ns;
        }
        destination[i].can_id = sampled[i].can_id;
        destination[i].state = destination[i].first_observed_ns
                                   ? ROADCAST_OBSERVATION_VALID
                                   : ROADCAST_OBSERVATION_NEVER_OBSERVED;
    }
}

static void sampler_publish(sampler_t *sampler, uint64_t sequence,
                            uint64_t timestamp_ns) {
    pthread_mutex_lock(&sampler->published_lock);
    memcpy(sampler->published, sampler->working, sizeof(sampler->published));
    sampler->published_sequence = sequence;
    sampler->published_timestamp_ns = timestamp_ns;
    pthread_mutex_unlock(&sampler->published_lock);
    uv_async_send(sampler->notification);
}

static void *sampler_thread_main(void *argument) {
    sampler_t *sampler = argument;
    uint64_t sequence = sampler->published_sequence;
    uint64_t next_tick = monotonic_ns();

    while (
        !atomic_load_explicit(&sampler->stop_requested, memory_order_acquire)) {
        next_tick += sampler->period_ns;
        sleep_until(next_tick);
        if (atomic_load_explicit(&sampler->stop_requested,
                                 memory_order_acquire))
            break;

        sequence++;
        roadcast_frame_t sampled[ROADCAST_FRAME_COUNT] = {0};
        int read_result = 0;
        int new_source = 0;
        if (sampler->fake) {
            initialize_fake_frames(sampled);
            update_fake_frames(sampled, sequence, sampler->fake == 2);
        } else {
            uint64_t generation = sampler->source.generation;
            read_result = roadcast_source_session_read(
                &sampler->source, sampled, monotonic_ns());
            new_source = read_result >= 0 &&
                         generation != sampler->source.generation;
            if (read_result == -1)
                atomic_fetch_add_explicit(&sampler->source_errors, 1,
                                          memory_order_relaxed);
            else if (read_result > 0)
                atomic_fetch_add_explicit(&sampler->fallback_batches, 1,
                                          memory_order_relaxed);
        }
        uint64_t timestamp_ns = monotonic_ns();
        merge_sampled_frames(sampler->working, sampled, timestamp_ns,
                             new_source);
        atomic_store_explicit(
            &sampler->source_state,
            read_result < 0   ? ROADCAST_SOURCE_STATE_UNAVAILABLE
            : read_result > 0 ? ROADCAST_SOURCE_STATE_DEGRADED
                              : ROADCAST_SOURCE_STATE_AVAILABLE,
            memory_order_relaxed);
        sampler_publish(sampler, sequence, timestamp_ns);

        uint64_t now = monotonic_ns();
        if (now > next_tick + sampler->period_ns)
            next_tick = now;
    }
    return NULL;
}

static int sampler_init(sampler_t *sampler, uv_async_t *notification,
                        uint32_t hz, int fake, const char *process_name) {
    memset(sampler, 0, sizeof(*sampler));
    sampler->notification = notification;
    sampler->period_ns = 1000000000ull / hz;
    sampler->fake = fake;
    atomic_init(&sampler->stop_requested, false);
    atomic_init(&sampler->source_errors, 0);
    atomic_init(&sampler->fallback_batches, 0);
    atomic_init(&sampler->source_state, ROADCAST_SOURCE_STATE_AVAILABLE);
    int error = pthread_mutex_init(&sampler->published_lock, NULL);
    if (error != 0) {
        errno = error;
        return -1;
    }

    roadcast_frame_t sampled[ROADCAST_FRAME_COUNT] = {0};
    int read_result = 0;
    if (fake) {
        initialize_fake_frames(sampled);
    } else {
        if (roadcast_source_session_init(&sampler->source, process_name) < 0) {
            pthread_mutex_destroy(&sampler->published_lock);
            return -1;
        }
        read_result = roadcast_source_session_read(&sampler->source, sampled,
                                                   monotonic_ns());
        if (read_result == -1)
            atomic_fetch_add_explicit(&sampler->source_errors, 1,
                                      memory_order_relaxed);
        else if (read_result > 0)
            atomic_fetch_add_explicit(&sampler->fallback_batches, 1,
                                      memory_order_relaxed);
    }
    merge_sampled_frames(sampler->working, sampled, monotonic_ns(), 1);
    atomic_store_explicit(&sampler->source_state,
                          read_result < 0   ? ROADCAST_SOURCE_STATE_UNAVAILABLE
                          : read_result > 0 ? ROADCAST_SOURCE_STATE_DEGRADED
                                            : ROADCAST_SOURCE_STATE_AVAILABLE,
                          memory_order_relaxed);

    memcpy(sampler->published, sampler->working, sizeof(sampler->published));
    sampler->published_sequence = 0;
    sampler->published_timestamp_ns = monotonic_ns();
    return 0;
}

static int sampler_start(sampler_t *sampler) {
    int error =
        pthread_create(&sampler->thread, NULL, sampler_thread_main, sampler);
    if (error != 0) {
        errno = error;
        return -1;
    }
    sampler->thread_started = 1;
    return 0;
}

static void sampler_stop(sampler_t *sampler) {
    atomic_store_explicit(&sampler->stop_requested, true, memory_order_release);
    if (sampler->thread_started) {
        pthread_join(sampler->thread, NULL);
        sampler->thread_started = 0;
    }
}

static void sampler_destroy(sampler_t *sampler) {
    sampler_stop(sampler);
    if (!sampler->fake)
        roadcast_source_session_close(&sampler->source);
    pthread_mutex_destroy(&sampler->published_lock);
}

static void sampler_read_latest(sampler_t *sampler,
                                roadcast_frame_t frames[ROADCAST_FRAME_COUNT],
                                uint64_t *sequence, uint64_t *timestamp_ns) {
    pthread_mutex_lock(&sampler->published_lock);
    memcpy(frames, sampler->published, sizeof(sampler->published));
    *sequence = sampler->published_sequence;
    *timestamp_ns = sampler->published_timestamp_ns;
    pthread_mutex_unlock(&sampler->published_lock);
}

static void client_close(roadcast_client_t *client);
static int drive_subscription_catchup(roadcast_client_t *client);
static int client_queue_message(roadcast_client_t *client, uint16_t type,
                                uint64_t sequence, uint64_t timestamp_ns,
                                const uint8_t *payload, size_t payload_length,
                                int droppable);

static void maybe_queue_resync(roadcast_client_t *client) {
    if (!client->active || !client->greeted || !client->needs_resync ||
        client->resync_queued || client->pending_bytes != 0)
        return;
    if (client_queue_message(client, ROADCAST_MSG_RESYNC_REQUIRED,
                             client->server->change_sequence,
                             client->server->sample_timestamp_ns, NULL, 0,
                             0) < 0) {
        client_close(client);
        return;
    }
    client->resync_queued = 1;
}

static void on_client_write(uv_write_t *request, int status) {
    output_slot_t *slot = request->data;
    roadcast_client_t *client = slot->client;
    if (client->pending_bytes >= slot->length)
        client->pending_bytes -= slot->length;
    else
        client->pending_bytes = 0;
    slot->length = 0;
    slot->in_use = 0;

    if (status < 0) {
        client_close(client);
        return;
    }
    if (client->catchup_active && drive_subscription_catchup(client) < 0) {
        client_close(client);
        return;
    }
    maybe_queue_resync(client);
}

static output_slot_t *find_output_slot(roadcast_client_t *client) {
    for (size_t i = 0; i < client->server->client_queue_slots; i++) {
        if (!client->output[i].in_use)
            return &client->output[i];
    }
    return NULL;
}

static int client_queue_message(roadcast_client_t *client, uint16_t type,
                                uint64_t sequence, uint64_t timestamp_ns,
                                const uint8_t *payload, size_t payload_length,
                                int droppable) {
    if (!client->active || payload_length > ROADCAST_MAX_PAYLOAD ||
        ROADCAST_HEADER_SIZE + payload_length > CLIENT_MESSAGE_CAP)
        return -1;

    size_t message_length = ROADCAST_HEADER_SIZE + payload_length;
    size_t queue_byte_limit =
        client->server->client_queue_slots * CLIENT_MESSAGE_CAP;
    size_t libuv_pending =
        uv_stream_get_write_queue_size((uv_stream_t *)&client->stream);
    output_slot_t *slot = find_output_slot(client);
    if (!slot || client->pending_bytes + message_length > queue_byte_limit ||
        libuv_pending + message_length > queue_byte_limit) {
        if (!droppable)
            return -1;
        if (!client->needs_resync) {
            fprintf(stderr,
                    "roadcastd: client queue overflow; resync required\n");
        }
        client->dropped_batches++;
        client->server->stats.queue_drops++;
        client->needs_resync = 1;
        client->subscribed = 0;
        client->setup_deadline_ns = monotonic_ns() + SETUP_TIMEOUT_NS;
        return 0;
    }

    roadcast_header_t header = {
        .version = ROADCAST_PROTOCOL_VERSION,
        .type = type,
        .flags = 0,
        .payload_bytes = (uint32_t)payload_length,
        .sequence = sequence,
        .timestamp_ns = timestamp_ns,
    };
    roadcast_encode_header(slot->data, &header);
    if (payload_length)
        memcpy(slot->data + ROADCAST_HEADER_SIZE, payload, payload_length);

    slot->client = client;
    slot->length = message_length;
    slot->in_use = 1;
    slot->request.data = slot;
    uv_buf_t buffer =
        uv_buf_init((char *)slot->data, (unsigned int)message_length);
    client->pending_bytes += message_length;
    int result = uv_write(&slot->request, (uv_stream_t *)&client->stream,
                          &buffer, 1, on_client_write);
    if (result < 0) {
        client->pending_bytes -= message_length;
        slot->length = 0;
        slot->in_use = 0;
        return -1;
    }
    return 0;
}

static void begin_snapshot(roadcast_client_t *client) {
    roadcast_server_t *server = client->server;
    memcpy(client->snapshot_frames, server->frames,
           sizeof(client->snapshot_frames));
    memcpy(client->snapshot_signals, server->signals,
           sizeof(client->snapshot_signals));
    client->snapshot_sample_sequence = server->sample_sequence;
    client->snapshot_change_sequence = server->change_sequence;
    client->snapshot_timestamp_ns = server->sample_timestamp_ns;
    client->snapshot_next_frame_index = 0;
    client->snapshot_next_index = 0;
    client->snapshot_active = 1;
    client->snapshot_frames_complete = 0;
    client->snapshot_complete = 0;
    client->setup_deadline_ns = monotonic_ns() + SETUP_TIMEOUT_NS;
    client->needs_resync = 0;
    client->resync_queued = 0;
}

static int queue_frame_snapshot_chunk(roadcast_client_t *client,
                                      uint32_t start_index) {
    if (start_index == 0 && !client->snapshot_active)
        begin_snapshot(client);
    if (!client->snapshot_active || client->snapshot_frames_complete ||
        start_index != client->snapshot_next_frame_index ||
        start_index >= ROADCAST_FRAME_COUNT)
        return -1;

    size_t count = ROADCAST_FRAME_COUNT - start_index;
    if (count > FRAMES_PER_MESSAGE)
        count = FRAMES_PER_MESSAGE;
    uint8_t payload[CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE];
    size_t length = roadcast_encode_frame_batch(
        payload, sizeof(payload), client->snapshot_sample_sequence,
        ROADCAST_FRAME_COUNT, start_index,
        client->snapshot_frames + start_index, NULL, count);
    if (!length)
        return -1;
    int result = client_queue_message(
        client, ROADCAST_MSG_SNAPSHOT, client->snapshot_change_sequence,
        client->snapshot_timestamp_ns, payload, length, 0);
    if (result < 0)
        return result;
    client->snapshot_next_frame_index += (uint32_t)count;
    if (client->snapshot_next_frame_index == ROADCAST_FRAME_COUNT)
        client->snapshot_frames_complete = 1;
    return result;
}

static int client_has_output_capacity(roadcast_client_t *client) {
    size_t libuv_pending =
        uv_stream_get_write_queue_size((uv_stream_t *)&client->stream);
    size_t queue_byte_limit =
        client->server->client_queue_slots * CLIENT_MESSAGE_CAP;
    return find_output_slot(client) != NULL &&
           client->pending_bytes + CLIENT_MESSAGE_CAP <= queue_byte_limit &&
           libuv_pending + CLIENT_MESSAGE_CAP <= queue_byte_limit;
}

static void begin_catchup_round(roadcast_client_t *client) {
    roadcast_server_t *server = client->server;
    client->catchup_frame_count = 0;
    client->catchup_frame_offset = 0;
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        if (server->frames[i].state != client->snapshot_frames[i].state ||
            memcmp(server->frames[i].data, client->snapshot_frames[i].data,
                   sizeof(server->frames[i].data)) != 0)
            client->catchup_frame_indices[client->catchup_frame_count++] =
                (uint16_t)i;
    }

    client->catchup_signal_count = 0;
    client->catchup_signal_offset = 0;
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        if (server->signals[i].raw != client->snapshot_signals[i].raw ||
            server->signals[i].state != client->snapshot_signals[i].state ||
            server->signals[i].first_observed_ns !=
                client->snapshot_signals[i].first_observed_ns ||
            server->signals[i].last_change_ns !=
                client->snapshot_signals[i].last_change_ns)
            client->catchup_signal_indices[client->catchup_signal_count++] = i;
    }
    memcpy(client->snapshot_frames, server->frames,
           sizeof(client->snapshot_frames));
    memcpy(client->snapshot_signals, server->signals,
           sizeof(client->snapshot_signals));
    client->catchup_sample_sequence = server->sample_sequence;
    client->catchup_change_sequence = server->change_sequence;
    client->catchup_timestamp_ns = server->sample_timestamp_ns;
}

static int drive_subscription_catchup(roadcast_client_t *client) {
    if (client->catchup_waiting_for_capacity &&
        client_has_output_capacity(client)) {
        client->catchup_waiting_for_capacity = 0;
        if (!client->catchup_resumption_reported) {
            fprintf(stderr,
                    "roadcastd: subscription catch-up resumed after queue "
                    "capacity became available\n");
            client->catchup_resumption_reported = 1;
        }
    }
    while (client->active && client->catchup_active &&
           client_has_output_capacity(client)) {
        if (client->catchup_frame_offset < client->catchup_frame_count) {
            size_t count =
                client->catchup_frame_count - client->catchup_frame_offset;
            if (count > FRAMES_PER_MESSAGE)
                count = FRAMES_PER_MESSAGE;
            uint8_t payload[CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE];
            size_t length = roadcast_encode_frame_batch(
                payload, sizeof(payload), client->catchup_sample_sequence,
                ROADCAST_FRAME_COUNT, UINT32_MAX, client->snapshot_frames,
                client->catchup_frame_indices + client->catchup_frame_offset,
                count);
            if (!length ||
                client_queue_message(client, ROADCAST_MSG_UPDATE_BATCH,
                                     client->catchup_change_sequence,
                                     client->catchup_timestamp_ns, payload,
                                     length, 0) < 0)
                return -1;
            client->catchup_frame_offset += count;
            continue;
        }

        if (client->catchup_signal_offset < client->catchup_signal_count) {
            size_t count =
                client->catchup_signal_count - client->catchup_signal_offset;
            if (count > SIGNALS_PER_MESSAGE)
                count = SIGNALS_PER_MESSAGE;
            uint8_t payload[CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE];
            size_t length = roadcast_encode_signal_batch(
                payload, sizeof(payload), client->catchup_sample_sequence,
                ROADCAST_SIGNAL_COUNT, UINT32_MAX, client->snapshot_signals,
                client->catchup_signal_indices + client->catchup_signal_offset,
                count);
            if (!length ||
                client_queue_message(client, ROADCAST_MSG_SIGNAL_UPDATE_BATCH,
                                     client->catchup_change_sequence,
                                     client->catchup_timestamp_ns, payload,
                                     length, 0) < 0)
                return -1;
            client->catchup_signal_offset += count;
            continue;
        }

        begin_catchup_round(client);
        if (client->catchup_frame_count || client->catchup_signal_count)
            continue;

        int result = client_queue_message(
            client, ROADCAST_MSG_SUBSCRIBED, client->server->change_sequence,
            client->server->sample_timestamp_ns, NULL, 0, 0);
        if (result < 0)
            return result;
        client->catchup_active = 0;
        client->subscribed = 1;
        client->setup_deadline_ns = 0;
    }
    if (client->active && client->catchup_active &&
        !client_has_output_capacity(client))
        client->catchup_waiting_for_capacity = 1;
    return 0;
}

static int queue_subscription_catchup(roadcast_client_t *client) {
    client->catchup_active = 1;
    begin_catchup_round(client);
    return drive_subscription_catchup(client);
}

static int queue_schema_chunk(roadcast_client_t *client, uint32_t start_index) {
    uint8_t payload[CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE];
    uint32_t encoded_count;
    size_t length = roadcast_encode_schema_chunk(payload, sizeof(payload),
                                                 start_index, &encoded_count);
    if (!length || !encoded_count)
        return -1;
    return client_queue_message(
        client, ROADCAST_MSG_SCHEMA_CHUNK, client->server->change_sequence,
        client->server->sample_timestamp_ns, payload, length, 0);
}

static int queue_signal_snapshot_chunk(roadcast_client_t *client,
                                       uint32_t start_index) {
    if (!client->snapshot_active || !client->snapshot_frames_complete ||
        start_index != client->snapshot_next_index ||
        start_index >= ROADCAST_SIGNAL_COUNT)
        return -1;
    uint32_t indices[SIGNALS_PER_MESSAGE];
    size_t count = ROADCAST_SIGNAL_COUNT - start_index;
    if (count > SIGNALS_PER_MESSAGE)
        count = SIGNALS_PER_MESSAGE;
    for (size_t i = 0; i < count; i++)
        indices[i] = start_index + (uint32_t)i;

    uint8_t payload[CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE];
    size_t length = roadcast_encode_signal_batch(
        payload, sizeof(payload), client->snapshot_sample_sequence,
        ROADCAST_SIGNAL_COUNT, start_index, client->snapshot_signals, indices,
        count);
    if (!length)
        return -1;
    int result = client_queue_message(
        client, ROADCAST_MSG_SIGNAL_SNAPSHOT_CHUNK,
        client->snapshot_change_sequence, client->snapshot_timestamp_ns,
        payload, length, 0);
    if (result < 0)
        return result;
    client->snapshot_next_index += (uint32_t)count;
    if (client->snapshot_next_index == ROADCAST_SIGNAL_COUNT) {
        client->snapshot_active = 0;
        client->snapshot_complete = 1;
    }
    return 0;
}

static int process_command(roadcast_client_t *client,
                           const roadcast_header_t *header,
                           const uint8_t *payload) {
    roadcast_server_t *server = client->server;
    if (header->version != ROADCAST_PROTOCOL_VERSION || header->flags != 0)
        return -1;

    switch (header->type) {
    case ROADCAST_MSG_HELLO: {
        uint16_t min_version;
        uint16_t max_version;
        uint32_t client_capabilities;
        if (client->greeted ||
            roadcast_decode_hello(payload, header->payload_bytes, &min_version,
                                  &max_version, &client_capabilities) < 0 ||
            min_version > ROADCAST_PROTOCOL_VERSION ||
            max_version < ROADCAST_PROTOCOL_VERSION)
            return -1;
        (void)client_capabilities;

        uint8_t welcome[ROADCAST_WELCOME_SIZE];
        size_t length = roadcast_encode_welcome(
            welcome, server->hz, ROADCAST_FRAME_COUNT, ROADCAST_SIGNAL_COUNT,
            CLIENT_INPUT_CAP - ROADCAST_HEADER_SIZE,
            CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE,
            ROADCAST_CAP_RAW_CAN | ROADCAST_CAP_DECODED_CAN,
            ROADCAST_SCHEMA_VERSION, ROADCAST_CAN_SCHEMA_HASH);
        if (client_queue_message(
                client, ROADCAST_MSG_WELCOME, server->change_sequence,
                server->sample_timestamp_ns, welcome, length, 0) < 0)
            return -1;
        client->greeted = 1;
        client->hello_deadline_ns = 0;
        client->setup_deadline_ns = monotonic_ns() + SETUP_TIMEOUT_NS;
        return 0;
    }
    case ROADCAST_MSG_GET_SNAPSHOT: {
        uint32_t start_index;
        if (!client->greeted ||
            roadcast_decode_index_request(payload, header->payload_bytes,
                                          &start_index) < 0)
            return -1;
        return queue_frame_snapshot_chunk(client, start_index);
    }
    case ROADCAST_MSG_SUBSCRIBE_ALL:
        if (!client->greeted || header->payload_bytes != 0 ||
            client->needs_resync || !client->snapshot_complete)
            return -1;
        return queue_subscription_catchup(client);
    case ROADCAST_MSG_PING:
        if (!client->greeted || header->payload_bytes != 0)
            return -1;
        return client_queue_message(client, ROADCAST_MSG_PONG,
                                    server->change_sequence,
                                    server->sample_timestamp_ns, NULL, 0, 0);
    case ROADCAST_MSG_GET_SCHEMA: {
        uint32_t start_index;
        if (!client->greeted ||
            roadcast_decode_index_request(payload, header->payload_bytes,
                                          &start_index) < 0)
            return -1;
        return queue_schema_chunk(client, start_index);
    }
    case ROADCAST_MSG_GET_SIGNAL_SNAPSHOT: {
        uint32_t start_index;
        if (!client->greeted ||
            roadcast_decode_index_request(payload, header->payload_bytes,
                                          &start_index) < 0)
            return -1;
        return queue_signal_snapshot_chunk(client, start_index);
    }
    default:
        return -1;
    }
}

static int process_client_input(roadcast_client_t *client) {
    size_t consumed = 0;
    while (client->input_length - consumed >= ROADCAST_HEADER_SIZE) {
        roadcast_header_t header;
        if (roadcast_decode_header(client->input + consumed, &header) < 0 ||
            header.payload_bytes > CLIENT_INPUT_CAP - ROADCAST_HEADER_SIZE)
            return -1;
        size_t message_length = ROADCAST_HEADER_SIZE + header.payload_bytes;
        if (client->input_length - consumed < message_length)
            break;
        if (process_command(client, &header,
                            client->input + consumed + ROADCAST_HEADER_SIZE) <
            0)
            return -1;
        consumed += message_length;
    }

    if (consumed) {
        memmove(client->input, client->input + consumed,
                client->input_length - consumed);
        client->input_length -= consumed;
    }
    return client->input_length == sizeof(client->input) ? -1 : 0;
}

static void allocate_client_input(uv_handle_t *handle, size_t suggested_size,
                                  uv_buf_t *buffer) {
    (void)suggested_size;
    roadcast_client_t *client = handle->data;
    if (!client->active || client->input_length >= sizeof(client->input)) {
        *buffer = uv_buf_init(NULL, 0);
        return;
    }
    *buffer = uv_buf_init(
        (char *)client->input + client->input_length,
        (unsigned int)(sizeof(client->input) - client->input_length));
}

static void on_client_read(uv_stream_t *stream, ssize_t bytes_read,
                           const uv_buf_t *buffer) {
    (void)buffer;
    roadcast_client_t *client = stream->data;
    if (bytes_read < 0) {
        client_close(client);
        return;
    }
    if (bytes_read == 0)
        return;
    client->input_length += (size_t)bytes_read;
    if (process_client_input(client) < 0)
        client_close(client);
}

static void on_client_closed(uv_handle_t *handle) {
    roadcast_client_t *client = handle->data;
    memset(client, 0, sizeof(*client));
}

static void client_close(roadcast_client_t *client) {
    if (!client->active || client->closing)
        return;
    client->active = 0;
    client->closing = 1;
    uv_read_stop((uv_stream_t *)&client->stream);
    uv_close((uv_handle_t *)&client->stream, on_client_closed);
}

static roadcast_client_t *find_available_client(roadcast_server_t *server) {
    for (size_t i = 0; i < MAX_CLIENTS; i++) {
        if (!server->clients[i].active && !server->clients[i].closing)
            return &server->clients[i];
    }
    return NULL;
}

static void on_rejected_client_closed(uv_handle_t *handle) { free(handle); }

static void reject_connection(uv_stream_t *listener) {
    rejected_client_t *rejected = calloc(1, sizeof(*rejected));
    if (!rejected)
        return;
    if (uv_pipe_init(listener->loop, &rejected->stream, 0) < 0) {
        free(rejected);
        return;
    }
    if (uv_accept(listener, (uv_stream_t *)&rejected->stream) < 0) {
        uv_close((uv_handle_t *)&rejected->stream, on_rejected_client_closed);
        return;
    }
    uv_close((uv_handle_t *)&rejected->stream, on_rejected_client_closed);
}

static int read_peer_credentials(uv_pipe_t *stream, uid_t *uid, pid_t *pid) {
    uv_os_fd_t descriptor;
    if (uv_fileno((uv_handle_t *)stream, &descriptor) < 0)
        return -1;
#if defined(__linux__) || defined(__ANDROID__)
    struct ucred credentials;
    socklen_t length = sizeof(credentials);
    if (getsockopt((int)descriptor, SOL_SOCKET, SO_PEERCRED, &credentials,
                   &length) < 0 ||
        length != sizeof(credentials))
        return -1;
    *uid = credentials.uid;
    *pid = credentials.pid;
    return 0;
#elif defined(__APPLE__)
    gid_t gid;
    if (getpeereid((int)descriptor, uid, &gid) < 0)
        return -1;
    *pid = 0;
    return 0;
#else
    (void)uid;
    (void)pid;
    errno = ENOTSUP;
    return -1;
#endif
}

static size_t active_clients_for_uid(const roadcast_server_t *server,
                                     uid_t uid) {
    size_t count = 0;
    for (size_t i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active && server->clients[i].peer_uid == uid)
            count++;
    }
    return count;
}

static void on_connection(uv_stream_t *listener, int status) {
    roadcast_server_t *server = listener->data;
    if (status < 0 || server->shutting_down)
        return;

    roadcast_client_t *client = find_available_client(server);
    if (!client) {
        reject_connection(listener);
        return;
    }

    memset(client, 0, sizeof(*client));
    client->server = server;
    if (uv_pipe_init(&server->loop, &client->stream, 0) < 0)
        return;
    client->stream.data = client;
    if (uv_accept(listener, (uv_stream_t *)&client->stream) < 0) {
        uv_close((uv_handle_t *)&client->stream, on_client_closed);
        client->closing = 1;
        return;
    }
    uid_t peer_uid;
    pid_t peer_pid;
    if (read_peer_credentials(&client->stream, &peer_uid, &peer_pid) < 0 ||
        active_clients_for_uid(server, peer_uid) >= MAX_CLIENTS_PER_UID) {
        server->stats.peer_limit_rejections++;
        client->active = 1;
        client_close(client);
        return;
    }
    client->peer_uid = peer_uid;
    client->peer_pid = peer_pid;
    uint64_t now = monotonic_ns();
    client->hello_deadline_ns = now + HELLO_TIMEOUT_NS;
    client->setup_deadline_ns = now + SETUP_TIMEOUT_NS;
    client->active = 1;
    uv_os_fd_t client_fd;
    if (uv_fileno((uv_handle_t *)&client->stream, &client_fd) == 0) {
        int send_buffer = CLIENT_SOCKET_SEND_BUFFER;
        setsockopt((int)client_fd, SOL_SOCKET, SO_SNDBUF, &send_buffer,
                   sizeof(send_buffer));
    }
    if (uv_read_start((uv_stream_t *)&client->stream, allocate_client_input,
                      on_client_read) < 0)
        client_close(client);
}

static size_t active_client_count(const roadcast_server_t *server) {
    size_t count = 0;
    for (size_t i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active)
            count++;
    }
    return count;
}

static void reconcile_signal_timestamps(roadcast_signal_value_t *latest,
                                        const roadcast_signal_value_t *canonical,
                                        uint64_t timestamp_ns) {
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        if (latest[i].state == ROADCAST_OBSERVATION_UNAVAILABLE) {
            if (canonical[i].first_observed_ns != 0) {
                latest[i].first_observed_ns = canonical[i].first_observed_ns;
                latest[i].last_change_ns = canonical[i].last_change_ns;
                latest[i].raw = canonical[i].raw;
                latest[i].physical = canonical[i].physical;
            } else {
                latest[i].first_observed_ns = 0;
                latest[i].last_change_ns = 0;
            }
            continue;
        }
        if (canonical[i].first_observed_ns != 0) {
            latest[i].first_observed_ns = canonical[i].first_observed_ns;
            if (latest[i].raw == canonical[i].raw &&
                latest[i].state == canonical[i].state) {
                latest[i].last_change_ns = canonical[i].last_change_ns;
            } else {
                latest[i].last_change_ns = timestamp_ns;
            }
        } else {
            if (latest[i].state == ROADCAST_OBSERVATION_VALID) {
                latest[i].first_observed_ns = timestamp_ns;
                latest[i].last_change_ns = timestamp_ns;
            } else {
                latest[i].first_observed_ns = 0;
                latest[i].last_change_ns = 0;
            }
        }
    }
}

static void distribute_latest_sample(roadcast_server_t *server) {
    roadcast_frame_t latest[ROADCAST_FRAME_COUNT];
    uint64_t sequence;
    uint64_t timestamp_ns;
    sampler_read_latest(&server->sampler, latest, &sequence, &timestamp_ns);
    if (sequence <= server->sample_sequence)
        return;

    uint64_t elapsed_ticks = sequence - server->sample_sequence;
    uint16_t changed[ROADCAST_FRAME_COUNT];
    size_t changed_count = 0;
    bool frame_changed[ROADCAST_FRAME_COUNT] = {false};
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        if (latest[i].state != server->frames[i].state ||
            memcmp(latest[i].data, server->frames[i].data,
                   sizeof(latest[i].data)) != 0) {
            changed[changed_count++] = (uint16_t)i;
            frame_changed[i] = true;
        }
    }

    if (!changed_count) {
        memcpy(server->frames, latest, sizeof(server->frames));
        server->sample_sequence = sequence;
        server->sample_timestamp_ns = timestamp_ns;
        server->stats.ticks += elapsed_ticks;
        server->stats.coalesced_samples += elapsed_ticks - 1;
        server->coalesced_samples += elapsed_ticks - 1;
        return;
    }

    roadcast_signal_value_t latest_signals[ROADCAST_SIGNAL_COUNT];
    memcpy(latest_signals, server->signals, sizeof(latest_signals));

    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        const roadcast_signal_definition_t *def = &ROADCAST_SIGNALS[i];
        if (!frame_changed[def->frame_index])
            continue;
        const roadcast_frame_t *frame = &latest[def->frame_index];
        latest_signals[i].raw = roadcast_decode_signal_raw(def, frame->data);
        latest_signals[i].physical =
            roadcast_decode_signal_physical(def, latest_signals[i].raw);
        latest_signals[i].state = frame->state;
        latest_signals[i].calibrated = def->calibrated;
    }

    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        const roadcast_signal_definition_t *def = &ROADCAST_SIGNALS[i];
        uint32_t inv = def->invalid_signal_index;
        if (inv != UINT32_MAX && (frame_changed[def->frame_index] ||
                                  frame_changed[ROADCAST_SIGNALS[inv].frame_index])) {
            if (latest_signals[i].state == ROADCAST_OBSERVATION_VALID &&
                latest_signals[inv].raw != 0)
                latest_signals[i].state = ROADCAST_OBSERVATION_INVALID;
        }
    }

    reconcile_signal_timestamps(latest_signals, server->signals, timestamp_ns);

    uint32_t changed_signals[ROADCAST_SIGNAL_COUNT];
    size_t changed_signal_count = 0;
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        if (latest_signals[i].raw != server->signals[i].raw ||
            latest_signals[i].state != server->signals[i].state ||
            latest_signals[i].first_observed_ns !=
                server->signals[i].first_observed_ns ||
            latest_signals[i].last_change_ns !=
                server->signals[i].last_change_ns)
            changed_signals[changed_signal_count++] = i;
    }

    memcpy(server->frames, latest, sizeof(server->frames));
    memcpy(server->signals, latest_signals, sizeof(server->signals));
    server->sample_sequence = sequence;
    server->sample_timestamp_ns = timestamp_ns;
    server->stats.ticks += elapsed_ticks;
    server->stats.coalesced_samples += elapsed_ticks - 1;
    server->coalesced_samples += elapsed_ticks - 1;
    server->stats.changed_frames += changed_count;
    if (!changed_count && !changed_signal_count)
        return;

    server->change_sequence++;

    int has_subscriber = 0;
    for (size_t i = 0; i < MAX_CLIENTS; i++) {
        const roadcast_client_t *client = &server->clients[i];
        if (client->active && client->subscribed && !client->needs_resync) {
            has_subscriber = 1;
            break;
        }
    }
    if (!has_subscriber)
        return;

    typedef struct {
        uint8_t payload[CLIENT_MESSAGE_CAP - ROADCAST_HEADER_SIZE];
        size_t length;
    } broadcast_chunk_t;

    broadcast_chunk_t frame_chunks[FRAME_BATCH_CHUNKS];
    size_t num_frame_chunks = 0;
    size_t frame_offset = 0;
    while (frame_offset < changed_count) {
        size_t chunk_count = changed_count - frame_offset;
        if (chunk_count > FRAMES_PER_MESSAGE)
            chunk_count = FRAMES_PER_MESSAGE;
        frame_chunks[num_frame_chunks].length = roadcast_encode_frame_batch(
            frame_chunks[num_frame_chunks].payload,
            sizeof(frame_chunks[num_frame_chunks].payload), sequence,
            ROADCAST_FRAME_COUNT, UINT32_MAX, server->frames,
            changed + frame_offset, chunk_count);
        num_frame_chunks++;
        server->stats.serialized_chunks++;
        frame_offset += chunk_count;
    }

    broadcast_chunk_t signal_chunks[SIGNAL_BATCH_CHUNKS];
    size_t num_signal_chunks = 0;
    size_t signal_offset = 0;
    while (signal_offset < changed_signal_count) {
        size_t chunk_count = changed_signal_count - signal_offset;
        if (chunk_count > SIGNALS_PER_MESSAGE)
            chunk_count = SIGNALS_PER_MESSAGE;
        signal_chunks[num_signal_chunks].length = roadcast_encode_signal_batch(
            signal_chunks[num_signal_chunks].payload,
            sizeof(signal_chunks[num_signal_chunks].payload), sequence,
            ROADCAST_SIGNAL_COUNT, UINT32_MAX, server->signals,
            changed_signals + signal_offset, chunk_count);
        num_signal_chunks++;
        server->stats.serialized_chunks++;
        signal_offset += chunk_count;
    }

    for (size_t i = 0; i < MAX_CLIENTS; i++) {
        roadcast_client_t *client = &server->clients[i];
        if (!client->active || !client->subscribed || client->needs_resync)
            continue;

        for (size_t c = 0; c < num_frame_chunks && client->active &&
                           !client->needs_resync;
             c++) {
            if (!frame_chunks[c].length ||
                client_queue_message(
                    client, ROADCAST_MSG_UPDATE_BATCH, server->change_sequence,
                    timestamp_ns, frame_chunks[c].payload,
                    frame_chunks[c].length, 1) < 0) {
                client_close(client);
                break;
            }
        }
        if (!client->active || client->needs_resync)
            continue;

        for (size_t c = 0; c < num_signal_chunks && client->active &&
                           !client->needs_resync;
             c++) {
            if (!signal_chunks[c].length ||
                client_queue_message(
                    client, ROADCAST_MSG_SIGNAL_UPDATE_BATCH,
                    server->change_sequence, timestamp_ns,
                    signal_chunks[c].payload, signal_chunks[c].length, 1) < 0) {
                client_close(client);
                break;
            }
        }
    }
}

static void on_sample_notification(uv_async_t *handle) {
    roadcast_server_t *server = handle->data;
    distribute_latest_sample(server);
}

static void on_heartbeat(uv_timer_t *timer) {
    roadcast_server_t *server = timer->data;
    uint64_t now = monotonic_ns();
    if (server->heartbeat_previous_ns && now > server->heartbeat_previous_ns) {
        uint64_t elapsed_ns = now - server->heartbeat_previous_ns;
        uint64_t elapsed_samples =
            server->sample_sequence - server->heartbeat_previous_sequence;
        server->effective_hz_millihz =
            (uint32_t)((elapsed_samples * UINT64_C(1000000000000)) /
                       elapsed_ns);
    }
    server->heartbeat_previous_ns = now;
    server->heartbeat_previous_sequence = server->sample_sequence;
    uint8_t source_state = atomic_load_explicit(&server->sampler.source_state,
                                                memory_order_relaxed);
    for (size_t i = 0; i < MAX_CLIENTS; i++) {
        roadcast_client_t *client = &server->clients[i];
        if (client->active && !client->greeted &&
            now >= client->hello_deadline_ns) {
            server->stats.hello_timeouts++;
            client_close(client);
            continue;
        }
        if (client->active && client->greeted && !client->subscribed &&
            client->setup_deadline_ns && now >= client->setup_deadline_ns) {
            server->stats.setup_timeouts++;
            client_close(client);
            continue;
        }
        if (!client->active || !client->greeted || !client->subscribed ||
            client->needs_resync)
            continue;
        uint8_t payload[ROADCAST_HEARTBEAT_SIZE];
        size_t length = roadcast_encode_heartbeat(
            payload, server->sample_sequence, server->change_sequence,
            client->dropped_batches, server->coalesced_samples,
            server->effective_hz_millihz, source_state);
        if (client_queue_message(
                client, ROADCAST_MSG_HEARTBEAT, server->change_sequence,
                server->sample_timestamp_ns, payload, length, 1) < 0)
            client_close(client);
    }
}

static void on_stats(uv_timer_t *timer) {
    roadcast_server_t *server = timer->data;
    uint64_t source_errors = atomic_load_explicit(
        &server->sampler.source_errors, memory_order_relaxed);
    uint64_t fallback_batches = atomic_load_explicit(
        &server->sampler.fallback_batches, memory_order_relaxed);
    fprintf(stderr,
            "roadcastd: %.2f Hz, clients=%zu, changes/tick=%.2f, "
            "source_errors=%llu, fallback_batches=%llu, coalesced=%llu, "
            "queue_drops=%llu, hello_timeouts=%llu, "
            "setup_timeouts=%llu, peer_rejections=%llu, "
            "serialized_chunks=%llu\n",
            server->stats.ticks / (STATS_INTERVAL_MS / 1000.0),
            active_client_count(server),
            server->stats.ticks
                ? server->stats.changed_frames / (double)server->stats.ticks
                : 0.0,
            (unsigned long long)(source_errors -
                                 server->stats.source_errors_at_start),
            (unsigned long long)(fallback_batches -
                                 server->stats.fallback_batches_at_start),
            (unsigned long long)server->stats.coalesced_samples,
            (unsigned long long)server->stats.queue_drops,
            (unsigned long long)server->stats.hello_timeouts,
            (unsigned long long)server->stats.setup_timeouts,
            (unsigned long long)server->stats.peer_limit_rejections,
            (unsigned long long)server->stats.serialized_chunks);
    memset(&server->stats, 0, sizeof(server->stats));
    server->stats.source_errors_at_start = source_errors;
    server->stats.fallback_batches_at_start = fallback_batches;
}

static void close_handle(uv_handle_t *handle) {
    if (!uv_is_closing(handle))
        uv_close(handle, NULL);
}

static void server_shutdown(roadcast_server_t *server) {
    if (server->shutting_down)
        return;
    server->shutting_down = 1;
    sampler_stop(&server->sampler);

    for (size_t i = 0; i < MAX_CLIENTS; i++)
        client_close(&server->clients[i]);
    if (server->listener_initialized)
        close_handle((uv_handle_t *)&server->listener);
    if (server->async_initialized)
        close_handle((uv_handle_t *)&server->sample_notification);
    if (server->heartbeat_initialized) {
        uv_timer_stop(&server->heartbeat_timer);
        close_handle((uv_handle_t *)&server->heartbeat_timer);
    }
    if (server->stats_initialized) {
        uv_timer_stop(&server->stats_timer);
        close_handle((uv_handle_t *)&server->stats_timer);
    }
    if (server->sigint_initialized) {
        uv_signal_stop(&server->sigint_handle);
        close_handle((uv_handle_t *)&server->sigint_handle);
    }
    if (server->sigterm_initialized) {
        uv_signal_stop(&server->sigterm_handle);
        close_handle((uv_handle_t *)&server->sigterm_handle);
    }
}

static void on_signal(uv_signal_t *handle, int signal_number) {
    (void)signal_number;
    roadcast_server_t *server = handle->data;
    server_shutdown(server);
}

static int initialize_libuv(roadcast_server_t *server,
                            const char *socket_name) {
    int result = uv_loop_init(&server->loop);
    if (result < 0)
        return result;

    result = uv_async_init(&server->loop, &server->sample_notification,
                           on_sample_notification);
    if (result < 0)
        return result;
    server->async_initialized = 1;
    server->sample_notification.data = server;

    int listener_fd = open_listener(socket_name, server->socket_path,
                                    sizeof(server->socket_path));
    if (listener_fd < 0)
        return -errno;
    result = uv_pipe_init(&server->loop, &server->listener, 0);
    if (result < 0) {
        close(listener_fd);
        return result;
    }
    server->listener_initialized = 1;
    server->listener.data = server;
    result = uv_pipe_open(&server->listener, listener_fd);
    if (result < 0) {
        close(listener_fd);
        return result;
    }
    result =
        uv_listen((uv_stream_t *)&server->listener, MAX_CLIENTS, on_connection);
    if (result < 0)
        return result;

    result = uv_timer_init(&server->loop, &server->heartbeat_timer);
    if (result < 0)
        return result;
    server->heartbeat_initialized = 1;
    server->heartbeat_timer.data = server;
    result = uv_timer_start(&server->heartbeat_timer, on_heartbeat,
                            HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
    if (result < 0)
        return result;

    result = uv_timer_init(&server->loop, &server->stats_timer);
    if (result < 0)
        return result;
    server->stats_initialized = 1;
    server->stats_timer.data = server;
    result = uv_timer_start(&server->stats_timer, on_stats, STATS_INTERVAL_MS,
                            STATS_INTERVAL_MS);
    if (result < 0)
        return result;

    result = uv_signal_init(&server->loop, &server->sigint_handle);
    if (result < 0)
        return result;
    server->sigint_initialized = 1;
    server->sigint_handle.data = server;
    result = uv_signal_start(&server->sigint_handle, on_signal, SIGINT);
    if (result < 0)
        return result;

    result = uv_signal_init(&server->loop, &server->sigterm_handle);
    if (result < 0)
        return result;
    server->sigterm_initialized = 1;
    server->sigterm_handle.data = server;
    return uv_signal_start(&server->sigterm_handle, on_signal, SIGTERM);
}

static int parse_positive_int(const char *value, int min, int max,
                              int *result) {
    char *end = NULL;
    long parsed = strtol(value, &end, 10);
    if (!value[0] || !end || *end || parsed < min || parsed > max)
        return -1;
    *result = (int)parsed;
    return 0;
}

static void usage(const char *program) {
    fprintf(stderr,
            "Usage: %s [--fake|--fake-dense] [--hz 1..240] "
            "[--socket @name|path] [--proc substring] "
            "[--client-queue-slots 1..%zu]\n",
            program, (size_t)CLIENT_QUEUE_SLOTS);
}

int main(int argc, char **argv) {
    const char *socket_name = DEFAULT_SOCKET;
    const char *process_name = DEFAULT_PROCESS;
    int hz = DEFAULT_HZ;
    int client_queue_slots = CLIENT_QUEUE_SLOTS;
    int fake = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--fake")) {
            fake = 1;
        } else if (!strcmp(argv[i], "--fake-dense")) {
            fake = 2;
        } else if (!strcmp(argv[i], "--hz") && i + 1 < argc) {
            if (parse_positive_int(argv[++i], 1, 240, &hz) < 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--socket") && i + 1 < argc) {
            socket_name = argv[++i];
        } else if (!strcmp(argv[i], "--proc") && i + 1 < argc) {
            process_name = argv[++i];
        } else if (!strcmp(argv[i], "--client-queue-slots") && i + 1 < argc) {
            if (parse_positive_int(argv[++i], 1, CLIENT_QUEUE_SLOTS,
                                   &client_queue_slots) < 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--help")) {
            usage(argv[0]);
            return 0;
        } else {
            usage(argv[0]);
            return 2;
        }
    }

    signal(SIGPIPE, SIG_IGN);

    roadcast_server_t server;
    memset(&server, 0, sizeof(server));
    server.hz = (uint32_t)hz;
    server.client_queue_slots = (uint32_t)client_queue_slots;
    server.effective_hz_millihz = server.hz * 1000;

    if (sampler_init(&server.sampler, &server.sample_notification, server.hz,
                     fake, process_name) < 0) {
        fprintf(stderr, "Failed to initialize %s source: %s\n",
                fake ? "fake" : "VHAL", strerror(errno));
        return 1;
    }
    sampler_read_latest(&server.sampler, server.frames, &server.sample_sequence,
                        &server.sample_timestamp_ns);
    roadcast_decode_signals(server.frames, server.signals);
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        if (server.signals[i].state == ROADCAST_OBSERVATION_VALID) {
            server.signals[i].first_observed_ns = server.sample_timestamp_ns;
            server.signals[i].last_change_ns = server.sample_timestamp_ns;
        } else {
            server.signals[i].first_observed_ns = 0;
            server.signals[i].last_change_ns = 0;
        }
    }
    server.stats.source_errors_at_start = atomic_load_explicit(
        &server.sampler.source_errors, memory_order_relaxed);
    server.stats.fallback_batches_at_start = atomic_load_explicit(
        &server.sampler.fallback_batches, memory_order_relaxed);

    int uv_result = initialize_libuv(&server, socket_name);
    if (uv_result < 0) {
        fprintf(stderr, "Failed to initialize transport %s: %s\n", socket_name,
                uv_strerror(uv_result));
        server_shutdown(&server);
        uv_run(&server.loop, UV_RUN_DEFAULT);
        uv_loop_close(&server.loop);
        if (server.socket_path[0])
            unlink(server.socket_path);
        sampler_destroy(&server.sampler);
        return 1;
    }

    pid_t initial_pid = fake ? 0 : server.sampler.source.vhal.pid;
    size_t initial_frames = fake ? ROADCAST_FRAME_COUNT
                                : server.sampler.source.vhal.resolved_count;
    if (sampler_start(&server.sampler) < 0) {
        fprintf(stderr, "Failed to start sampler: %s\n", strerror(errno));
        server_shutdown(&server);
        uv_run(&server.loop, UV_RUN_DEFAULT);
        uv_loop_close(&server.loop);
        if (server.socket_path[0])
            unlink(server.socket_path);
        sampler_destroy(&server.sampler);
        return 1;
    }

    if (!fake && setpriority(PRIO_PROCESS, 0, -10) < 0) {
        fprintf(stderr, "Warning: setpriority(-10) failed: %s\n",
                strerror(errno));
    }
    fprintf(stderr,
            "roadcastd: source=%s pid=%d frames=%zu/%zu hz=%u socket=%s\n",
            fake ? "fake" : "vhal", initial_pid, initial_frames,
            ROADCAST_FRAME_COUNT, server.hz, socket_name);

    uv_run(&server.loop, UV_RUN_DEFAULT);
    server_shutdown(&server);
    uv_run(&server.loop, UV_RUN_DEFAULT);
    int close_result = uv_loop_close(&server.loop);
    if (server.socket_path[0])
        unlink(server.socket_path);
    sampler_destroy(&server.sampler);
    if (close_result < 0) {
        fprintf(stderr, "Failed to close event loop: %s\n",
                uv_strerror(close_result));
        return 1;
    }
    return 0;
}
