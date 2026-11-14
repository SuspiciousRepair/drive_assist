#define _GNU_SOURCE

#include "roadcast_client.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define CLIENT_READ_TIMEOUT_MS 3000
#define CLIENT_SETUP_TIMEOUT_MS 10000
#define CLIENT_MAX_FRAMES 65535u
#define CLIENT_MAX_SIGNALS 100000u
#define CAN_ID_LOOKUP_CAP 2048u

struct roadcast_client {
    int fd;
    pthread_t reader_thread;
    pthread_mutex_t cache_lock;
    atomic_bool stop_requested;
    int reader_started;

    uint8_t *payload;
    size_t payload_capacity;
    roadcast_frame_t *frame_scratch;
    size_t frame_scratch_capacity;
    roadcast_signal_update_t *signal_scratch;
    size_t signal_scratch_capacity;
    roadcast_schema_entry_t *schema;
    roadcast_frame_t *frames;
    roadcast_signal_value_t *signals;
    uint16_t can_id_to_index[CAN_ID_LOOKUP_CAP];

    uint32_t hz;
    uint32_t frame_count;
    uint32_t signal_count;
    uint32_t schema_version;
    uint64_t schema_hash;
    uint32_t max_request_payload;
    uint32_t max_response_payload;

    uint64_t sample_sequence;
    uint64_t change_sequence;
    uint64_t sample_timestamp_ns;
    uint64_t dropped_batches;
    uint64_t coalesced_samples;
    uint64_t resynchronizations;
    uint32_t effective_hz_millihz;
    uint8_t source_state;
    uint8_t connected;
};

typedef struct {
    roadcast_frame_t *frames;
    roadcast_signal_value_t *signals;
    uint64_t sample_sequence;
    uint64_t change_sequence;
    uint64_t sample_timestamp_ns;
} synchronization_t;

static uint64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}

static socklen_t fill_unix_address(struct sockaddr_un *address,
                                   const char *name) {
    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    size_t length = strlen(name);
    if (!length || length >= sizeof(address->sun_path)) {
        errno = EINVAL;
        return 0;
    }
    if (name[0] == '@') {
#if defined(__linux__) || defined(__ANDROID__)
        address->sun_path[0] = '\0';
        memcpy(address->sun_path + 1, name + 1, length - 1);
        return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + length);
#else
        errno = EAFNOSUPPORT;
        return 0;
#endif
    }
    memcpy(address->sun_path, name, length + 1);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + length + 1);
}

static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0)
        return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int wait_descriptor(int fd, short events, uint64_t deadline_ns) {
    for (;;) {
        uint64_t now = monotonic_ns();
        if (now >= deadline_ns) {
            errno = ETIMEDOUT;
            return -1;
        }
        uint64_t remaining_ns = deadline_ns - now;
        int timeout_ms = (int)((remaining_ns + 999999ULL) / 1000000ULL);
        if (timeout_ms <= 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        struct pollfd descriptor = {
            .fd = fd,
            .events = events,
            .revents = 0,
        };
        int result = poll(&descriptor, 1, timeout_ms);
        if (result > 0) {
            if (events & POLLIN) {
                if (descriptor.revents & POLLIN)
                    return 0;
                if (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    errno = EIO;
                    return -1;
                }
            } else if (events & POLLOUT) {
                if (descriptor.revents & POLLOUT)
                    return 0;
                if (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    errno = EIO;
                    return -1;
                }
            }
            return 0;
        }
        if (result < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        errno = ETIMEDOUT;
        return -1;
    }
}

static int connect_socket(const char *socket_name, uint64_t deadline_ns) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    if (set_nonblocking(fd) < 0) {
        close(fd);
        return -1;
    }
    struct sockaddr_un address;
    socklen_t length = fill_unix_address(&address, socket_name);
    if (!length) {
        close(fd);
        return -1;
    }
    int rc = connect(fd, (const struct sockaddr *)&address, length);
    if (rc < 0) {
        if (errno == EINPROGRESS || errno == EAGAIN) {
            if (wait_descriptor(fd, POLLOUT, deadline_ns) < 0) {
                close(fd);
                return -1;
            }
            int so_error = 0;
            socklen_t len = sizeof(so_error);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_error, &len) < 0 ||
                so_error != 0) {
                if (so_error)
                    errno = so_error;
                close(fd);
                return -1;
            }
        } else {
            close(fd);
            return -1;
        }
    }
    return fd;
}

static int write_all(int fd, const uint8_t *data, size_t length,
                     uint64_t deadline_ns) {
    while (length) {
        ssize_t written = send(fd, data, length, MSG_NOSIGNAL);
        if (written > 0) {
            data += (size_t)written;
            length -= (size_t)written;
            continue;
        }
        if (written < 0) {
            if (errno == EINTR)
                continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (wait_descriptor(fd, POLLOUT, deadline_ns) < 0)
                    return -1;
                continue;
            }
            return -1;
        }
        return -1;
    }
    return 0;
}

static int send_message(roadcast_client_t *client, uint16_t type,
                        const uint8_t *payload, size_t payload_length,
                        uint64_t max_deadline_ns) {
    if (payload_length > client->max_request_payload)
        return -1;
    uint64_t op_deadline_ns =
        monotonic_ns() + (uint64_t)CLIENT_READ_TIMEOUT_MS * 1000000ULL;
    uint64_t deadline_ns = max_deadline_ns
                               ? (max_deadline_ns < op_deadline_ns
                                      ? max_deadline_ns
                                      : op_deadline_ns)
                               : op_deadline_ns;
    uint8_t header_bytes[ROADCAST_HEADER_SIZE];
    roadcast_header_t header = {
        .version = ROADCAST_PROTOCOL_VERSION,
        .type = type,
        .flags = 0,
        .payload_bytes = (uint32_t)payload_length,
        .sequence = 0,
        .timestamp_ns = monotonic_ns(),
    };
    roadcast_encode_header(header_bytes, &header);
    if (write_all(client->fd, header_bytes, sizeof(header_bytes), deadline_ns) <
        0)
        return -1;
    if (payload_length &&
        write_all(client->fd, payload, payload_length, deadline_ns) < 0)
        return -1;
    return 0;
}

static int read_exact(roadcast_client_t *client, uint8_t *output,
                      size_t length, uint64_t deadline_ns) {
    while (length) {
        ssize_t count = recv(client->fd, output, length, 0);
        if (count > 0) {
            output += (size_t)count;
            length -= (size_t)count;
            continue;
        }
        if (count == 0) {
            errno = EPIPE;
            return -1;
        }
        if (count < 0) {
            if (errno == EINTR)
                continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (wait_descriptor(client->fd, POLLIN, deadline_ns) < 0)
                    return -1;
                continue;
            }
            return -1;
        }
        return -1;
    }
    return 0;
}

static int read_message(roadcast_client_t *client, roadcast_header_t *header,
                        uint64_t max_deadline_ns) {
    uint64_t op_deadline_ns =
        monotonic_ns() + (uint64_t)CLIENT_READ_TIMEOUT_MS * 1000000ULL;
    uint64_t deadline_ns = max_deadline_ns
                               ? (max_deadline_ns < op_deadline_ns
                                      ? max_deadline_ns
                                      : op_deadline_ns)
                               : op_deadline_ns;
    uint8_t header_bytes[ROADCAST_HEADER_SIZE];
    if (read_exact(client, header_bytes, sizeof(header_bytes), deadline_ns) <
            0 ||
        roadcast_decode_header(header_bytes, header) < 0 ||
        header->version != ROADCAST_PROTOCOL_VERSION || header->flags != 0 ||
        header->payload_bytes > client->payload_capacity)
        return -1;
    if (header->payload_bytes &&
        read_exact(client, client->payload, header->payload_bytes,
                   deadline_ns) < 0)
        return -1;
    return 0;
}

static int request_schema(roadcast_client_t *client, uint64_t deadline_ns) {
    uint32_t next_index = 0;
    while (next_index < client->signal_count) {
        uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
        roadcast_encode_index_request(request, next_index);
        roadcast_header_t header;
        if (send_message(client, ROADCAST_MSG_GET_SCHEMA, request,
                         sizeof(request), deadline_ns) < 0 ||
            read_message(client, &header, deadline_ns) < 0 ||
            header.type != ROADCAST_MSG_SCHEMA_CHUNK)
            return -1;

        uint32_t schema_version;
        uint32_t total_count;
        uint32_t start_index;
        uint64_t schema_hash;
        size_t entry_count;
        if (roadcast_decode_schema_chunk(
                client->payload, header.payload_bytes, &schema_version,
                &total_count, &start_index, &schema_hash,
                client->schema + next_index, client->signal_count - next_index,
                &entry_count) < 0 ||
            schema_version != client->schema_version ||
            schema_hash != client->schema_hash ||
            total_count != client->signal_count || start_index != next_index ||
            entry_count == 0)
            return -1;
        next_index += (uint32_t)entry_count;
    }
    return 0;
}

static int request_frame_snapshot(roadcast_client_t *client,
                                  synchronization_t *sync,
                                  uint64_t deadline_ns) {
    uint32_t next_index = 0;
    while (next_index < client->frame_count) {
        uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
        roadcast_encode_index_request(request, next_index);
        roadcast_header_t header;
        if (send_message(client, ROADCAST_MSG_GET_SNAPSHOT, request,
                         sizeof(request), deadline_ns) < 0 ||
            read_message(client, &header, deadline_ns) < 0 ||
            header.type != ROADCAST_MSG_SNAPSHOT)
            return -1;

        uint64_t sample_sequence;
        uint32_t total_count;
        uint32_t start_index;
        size_t frame_count;
        if (roadcast_decode_frame_batch(
                client->payload, header.payload_bytes, &sample_sequence,
                &total_count, &start_index, sync->frames + next_index,
                client->frame_count - next_index, &frame_count) < 0 ||
            total_count != client->frame_count || start_index != next_index ||
            frame_count == 0)
            return -1;
        if (next_index == 0) {
            sync->sample_sequence = sample_sequence;
            sync->change_sequence = header.sequence;
            sync->sample_timestamp_ns = header.timestamp_ns;
        } else if (sample_sequence != sync->sample_sequence ||
                   header.sequence != sync->change_sequence ||
                   header.timestamp_ns != sync->sample_timestamp_ns) {
            return -1;
        }
        next_index += (uint32_t)frame_count;
    }
    for (uint32_t i = 0; i < client->frame_count; i++) {
        uint16_t can_id = sync->frames[i].can_id;
        if (can_id < CAN_ID_LOOKUP_CAP)
            client->can_id_to_index[can_id] = (uint16_t)i;
    }
    return 0;
}

static int request_signal_snapshot(roadcast_client_t *client,
                                   synchronization_t *sync,
                                   uint64_t deadline_ns) {
    uint32_t next_index = 0;
    while (next_index < client->signal_count) {
        uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
        roadcast_encode_index_request(request, next_index);
        roadcast_header_t header;
        if (send_message(client, ROADCAST_MSG_GET_SIGNAL_SNAPSHOT, request,
                         sizeof(request), deadline_ns) < 0 ||
            read_message(client, &header, deadline_ns) < 0 ||
            header.type != ROADCAST_MSG_SIGNAL_SNAPSHOT_CHUNK ||
            header.sequence != sync->change_sequence ||
            header.timestamp_ns != sync->sample_timestamp_ns)
            return -1;

        uint64_t sample_sequence;
        uint32_t total_count;
        uint32_t start_index;
        size_t update_count;
        if (roadcast_decode_signal_batch(
                client->payload, header.payload_bytes, &sample_sequence,
                &total_count, &start_index, client->signal_scratch,
                client->signal_scratch_capacity, &update_count) < 0 ||
            sample_sequence != sync->sample_sequence ||
            total_count != client->signal_count || start_index != next_index ||
            update_count == 0)
            return -1;
        for (size_t i = 0; i < update_count; i++) {
            roadcast_signal_update_t *update = &client->signal_scratch[i];
            if (update->index != next_index + i ||
                update->index >= client->signal_count)
                return -1;
            sync->signals[update->index] = update->value;
        }
        next_index += (uint32_t)update_count;
    }
    return 0;
}

static int decode_frame_batch(roadcast_client_t *client,
                              const roadcast_header_t *header,
                              uint64_t *sample_sequence, size_t *frame_count) {
    uint32_t total_count;
    uint32_t start_index;
    if (roadcast_decode_frame_batch(
            client->payload, header->payload_bytes, sample_sequence,
            &total_count, &start_index, client->frame_scratch,
            client->frame_scratch_capacity, frame_count) < 0 ||
        total_count != client->frame_count || start_index != UINT32_MAX)
        return -1;
    return 0;
}

static int apply_decoded_frames(roadcast_client_t *client,
                                synchronization_t *sync,
                                const roadcast_header_t *header,
                                uint64_t sample_sequence, size_t frame_count) {
    for (size_t i = 0; i < frame_count; i++) {
        uint16_t can_id = client->frame_scratch[i].can_id;
        uint16_t target = (can_id < CAN_ID_LOOKUP_CAP)
                              ? client->can_id_to_index[can_id]
                              : UINT16_MAX;
        if (target >= client->frame_count)
            return -1;
        sync->frames[target] = client->frame_scratch[i];
    }
    sync->sample_sequence = sample_sequence;
    sync->change_sequence = header->sequence;
    sync->sample_timestamp_ns = header->timestamp_ns;
    return 0;
}

static int apply_frame_batch(roadcast_client_t *client, synchronization_t *sync,
                             const roadcast_header_t *header) {
    uint64_t sample_sequence;
    size_t frame_count;
    if (decode_frame_batch(client, header, &sample_sequence, &frame_count) < 0)
        return -1;
    return apply_decoded_frames(client, sync, header, sample_sequence,
                                frame_count);
}

static int decode_signal_batch(roadcast_client_t *client,
                               const roadcast_header_t *header,
                               uint64_t *sample_sequence,
                               size_t *update_count) {
    uint32_t total_count;
    uint32_t start_index;
    if (roadcast_decode_signal_batch(
            client->payload, header->payload_bytes, sample_sequence,
            &total_count, &start_index, client->signal_scratch,
            client->signal_scratch_capacity, update_count) < 0 ||
        total_count != client->signal_count || start_index != UINT32_MAX)
        return -1;
    return 0;
}

static int apply_decoded_signals(roadcast_client_t *client,
                                 synchronization_t *sync,
                                 const roadcast_header_t *header,
                                 uint64_t sample_sequence,
                                 size_t update_count) {
    for (size_t i = 0; i < update_count; i++) {
        roadcast_signal_update_t *update = &client->signal_scratch[i];
        if (update->index >= client->signal_count)
            return -1;
        sync->signals[update->index] = update->value;
    }
    sync->sample_sequence = sample_sequence;
    sync->change_sequence = header->sequence;
    sync->sample_timestamp_ns = header->timestamp_ns;
    return 0;
}

static int apply_signal_batch(roadcast_client_t *client,
                              synchronization_t *sync,
                              const roadcast_header_t *header) {
    uint64_t sample_sequence;
    size_t update_count;
    if (decode_signal_batch(client, header, &sample_sequence, &update_count) <
        0)
        return -1;
    return apply_decoded_signals(client, sync, header, sample_sequence,
                                 update_count);
}

static int complete_subscription(roadcast_client_t *client,
                                 synchronization_t *sync,
                                 uint64_t deadline_ns) {
    if (send_message(client, ROADCAST_MSG_SUBSCRIBE_ALL, NULL, 0,
                     deadline_ns) < 0)
        return -1;
    for (;;) {
        roadcast_header_t header;
        if (read_message(client, &header, deadline_ns) < 0)
            return -1;
        if (header.type == ROADCAST_MSG_SUBSCRIBED) {
            sync->change_sequence = header.sequence;
            sync->sample_timestamp_ns = header.timestamp_ns;
            return 0;
        }
        if (header.type == ROADCAST_MSG_UPDATE_BATCH) {
            if (apply_frame_batch(client, sync, &header) < 0)
                return -1;
            continue;
        }
        if (header.type == ROADCAST_MSG_SIGNAL_UPDATE_BATCH) {
            if (apply_signal_batch(client, sync, &header) < 0)
                return -1;
            continue;
        }
        return -1;
    }
}

static int initialize_synchronization(roadcast_client_t *client,
                                      synchronization_t *sync) {
    memset(sync, 0, sizeof(*sync));
    sync->frames = calloc(client->frame_count, sizeof(*sync->frames));
    sync->signals = calloc(client->signal_count, sizeof(*sync->signals));
    if (!sync->frames || !sync->signals) {
        free(sync->frames);
        free(sync->signals);
        memset(sync, 0, sizeof(*sync));
        return -1;
    }
    return 0;
}

static void destroy_synchronization(synchronization_t *sync) {
    free(sync->frames);
    free(sync->signals);
    memset(sync, 0, sizeof(*sync));
}

static int synchronize(roadcast_client_t *client, synchronization_t *sync,
                       uint64_t deadline_ns) {
    return request_frame_snapshot(client, sync, deadline_ns) < 0 ||
                   request_signal_snapshot(client, sync, deadline_ns) < 0 ||
                   complete_subscription(client, sync, deadline_ns) < 0
               ? -1
               : 0;
}

static void commit_synchronization(roadcast_client_t *client,
                                   const synchronization_t *sync) {
    pthread_mutex_lock(&client->cache_lock);
    memcpy(client->frames, sync->frames,
           client->frame_count * sizeof(*client->frames));
    memcpy(client->signals, sync->signals,
           client->signal_count * sizeof(*client->signals));
    client->sample_sequence = sync->sample_sequence;
    client->change_sequence = sync->change_sequence;
    client->sample_timestamp_ns = sync->sample_timestamp_ns;
    client->connected = 1;
    for (size_t i = 0; i < CAN_ID_LOOKUP_CAP; i++)
        client->can_id_to_index[i] = UINT16_MAX;
    for (uint32_t i = 0; i < client->frame_count; i++) {
        uint16_t can_id = client->frames[i].can_id;
        if (can_id < CAN_ID_LOOKUP_CAP)
            client->can_id_to_index[can_id] = (uint16_t)i;
    }
    pthread_mutex_unlock(&client->cache_lock);
}

static int resynchronize(roadcast_client_t *client) {
    uint64_t resync_deadline_ns =
        monotonic_ns() + (uint64_t)CLIENT_SETUP_TIMEOUT_MS * 1000000ULL;
    synchronization_t sync;
    if (initialize_synchronization(client, &sync) < 0)
        return -1;
    int result = synchronize(client, &sync, resync_deadline_ns);
    if (result == 0) {
        commit_synchronization(client, &sync);
        pthread_mutex_lock(&client->cache_lock);
        client->resynchronizations++;
        pthread_mutex_unlock(&client->cache_lock);
    }
    destroy_synchronization(&sync);
    return result;
}

static int apply_live_frame_batch(roadcast_client_t *client,
                                  const roadcast_header_t *header) {
    uint64_t sample_sequence;
    size_t frame_count;
    if (decode_frame_batch(client, header, &sample_sequence, &frame_count) < 0)
        return -1;
    pthread_mutex_lock(&client->cache_lock);
    synchronization_t sync = {
        .frames = client->frames,
        .signals = client->signals,
        .sample_sequence = client->sample_sequence,
        .change_sequence = client->change_sequence,
        .sample_timestamp_ns = client->sample_timestamp_ns,
    };
    int result = apply_decoded_frames(client, &sync, header, sample_sequence,
                                      frame_count);
    if (result == 0) {
        client->sample_sequence = sync.sample_sequence;
        client->change_sequence = sync.change_sequence;
        client->sample_timestamp_ns = sync.sample_timestamp_ns;
    }
    pthread_mutex_unlock(&client->cache_lock);
    return result;
}

static int apply_live_signal_batch(roadcast_client_t *client,
                                   const roadcast_header_t *header) {
    uint64_t sample_sequence;
    size_t update_count;
    if (decode_signal_batch(client, header, &sample_sequence, &update_count) <
        0)
        return -1;
    pthread_mutex_lock(&client->cache_lock);
    synchronization_t sync = {
        .frames = client->frames,
        .signals = client->signals,
        .sample_sequence = client->sample_sequence,
        .change_sequence = client->change_sequence,
        .sample_timestamp_ns = client->sample_timestamp_ns,
    };
    int result = apply_decoded_signals(client, &sync, header, sample_sequence,
                                       update_count);
    if (result == 0) {
        client->sample_sequence = sync.sample_sequence;
        client->change_sequence = sync.change_sequence;
        client->sample_timestamp_ns = sync.sample_timestamp_ns;
    }
    pthread_mutex_unlock(&client->cache_lock);
    return result;
}

static int apply_heartbeat(roadcast_client_t *client,
                           const roadcast_header_t *header) {
    uint64_t sample_sequence;
    uint64_t change_sequence;
    uint64_t dropped_batches;
    uint64_t coalesced_samples;
    uint32_t effective_hz_millihz;
    uint8_t source_state;
    if (roadcast_decode_heartbeat(client->payload, header->payload_bytes,
                                  &sample_sequence, &change_sequence,
                                  &dropped_batches, &coalesced_samples,
                                  &effective_hz_millihz, &source_state) < 0 ||
        change_sequence != header->sequence)
        return -1;
    pthread_mutex_lock(&client->cache_lock);
    client->sample_sequence = sample_sequence;
    client->change_sequence = change_sequence;
    client->sample_timestamp_ns = header->timestamp_ns;
    client->dropped_batches = dropped_batches;
    client->coalesced_samples = coalesced_samples;
    client->effective_hz_millihz = effective_hz_millihz;
    client->source_state = source_state;
    pthread_mutex_unlock(&client->cache_lock);
    return 0;
}

static void mark_disconnected(roadcast_client_t *client) {
    pthread_mutex_lock(&client->cache_lock);
    client->connected = 0;
    pthread_mutex_unlock(&client->cache_lock);
}

static void *reader_thread_main(void *argument) {
    roadcast_client_t *client = argument;
    while (
        !atomic_load_explicit(&client->stop_requested, memory_order_acquire)) {
        roadcast_header_t header;
        if (read_message(client, &header, 0) < 0)
            break;
        int result;
        switch (header.type) {
        case ROADCAST_MSG_UPDATE_BATCH:
            result = apply_live_frame_batch(client, &header);
            break;
        case ROADCAST_MSG_SIGNAL_UPDATE_BATCH:
            result = apply_live_signal_batch(client, &header);
            break;
        case ROADCAST_MSG_HEARTBEAT:
            result = apply_heartbeat(client, &header);
            break;
        case ROADCAST_MSG_RESYNC_REQUIRED:
            result = header.payload_bytes == 0 ? resynchronize(client) : -1;
            break;
        default:
            result = -1;
            break;
        }
        if (result < 0)
            break;
    }
    mark_disconnected(client);
    return NULL;
}

static int perform_handshake(roadcast_client_t *client, uint64_t deadline_ns) {
    uint8_t hello[8];
    size_t hello_length = roadcast_encode_hello(hello);
    roadcast_header_t header;
    if (send_message(client, ROADCAST_MSG_HELLO, hello, hello_length,
                     deadline_ns) < 0 ||
        read_message(client, &header, deadline_ns) < 0 ||
        header.type != ROADCAST_MSG_WELCOME)
        return -1;

    uint32_t capabilities;
    if (roadcast_decode_welcome(
            client->payload, header.payload_bytes, &client->hz,
            &client->frame_count, &client->signal_count,
            &client->max_request_payload, &client->max_response_payload,
            &capabilities, &client->schema_version, &client->schema_hash) < 0 ||
        !(capabilities & ROADCAST_CAP_RAW_CAN) ||
        !(capabilities & ROADCAST_CAP_DECODED_CAN) ||
        client->frame_count == 0 || client->frame_count > CLIENT_MAX_FRAMES ||
        client->signal_count == 0 ||
        client->signal_count > CLIENT_MAX_SIGNALS ||
        client->max_request_payload < 8 ||
        client->max_response_payload < ROADCAST_WELCOME_SIZE ||
        client->max_response_payload > ROADCAST_MAX_PAYLOAD)
        return -1;
    return 0;
}

roadcast_client_t *roadcast_client_connect(const char *socket_name,
                                           int *error) {
    if (error)
        *error = ROADCAST_CLIENT_OK;
    if (!socket_name || !socket_name[0]) {
        if (error)
            *error = ROADCAST_CLIENT_ERR_ARGUMENT;
        return NULL;
    }

    roadcast_client_t *client = calloc(1, sizeof(*client));
    if (!client) {
        if (error)
            *error = ROADCAST_CLIENT_ERR_MEMORY;
        return NULL;
    }
    client->fd = -1;
    client->max_request_payload = 8;
    client->payload_capacity = ROADCAST_WELCOME_SIZE;
    client->payload = malloc(client->payload_capacity);
    for (size_t i = 0; i < CAN_ID_LOOKUP_CAP; i++)
        client->can_id_to_index[i] = UINT16_MAX;
    if (!client->payload ||
        pthread_mutex_init(&client->cache_lock, NULL) != 0) {
        if (error)
            *error = ROADCAST_CLIENT_ERR_MEMORY;
        free(client->payload);
        free(client);
        return NULL;
    }
    atomic_init(&client->stop_requested, false);

    int failure = ROADCAST_CLIENT_ERR_SOCKET;
    uint64_t setup_deadline_ns =
        monotonic_ns() + (uint64_t)CLIENT_SETUP_TIMEOUT_MS * 1000000ULL;
    client->fd = connect_socket(socket_name, setup_deadline_ns);
    if (client->fd < 0)
        goto fail;
    failure = ROADCAST_CLIENT_ERR_HANDSHAKE;
    if (perform_handshake(client, setup_deadline_ns) < 0)
        goto fail;

    uint8_t *payload = realloc(client->payload, client->max_response_payload);
    if (!payload) {
        failure = ROADCAST_CLIENT_ERR_MEMORY;
        goto fail;
    }
    client->payload = payload;
    client->payload_capacity = client->max_response_payload;
    client->schema = calloc(client->signal_count, sizeof(*client->schema));
    client->frames = calloc(client->frame_count, sizeof(*client->frames));
    client->signals = calloc(client->signal_count, sizeof(*client->signals));
    client->frame_scratch_capacity =
        (client->max_response_payload - ROADCAST_BATCH_PREFIX_SIZE) /
        ROADCAST_FRAME_WIRE_SIZE;
    client->signal_scratch_capacity =
        (client->max_response_payload - ROADCAST_BATCH_PREFIX_SIZE) /
        ROADCAST_SIGNAL_WIRE_SIZE;
    client->frame_scratch =
        calloc(client->frame_scratch_capacity, sizeof(*client->frame_scratch));
    client->signal_scratch = calloc(client->signal_scratch_capacity,
                                    sizeof(*client->signal_scratch));
    if (!client->schema || !client->frames || !client->signals ||
        !client->frame_scratch || !client->signal_scratch) {
        failure = ROADCAST_CLIENT_ERR_MEMORY;
        goto fail;
    }

    failure = ROADCAST_CLIENT_ERR_SCHEMA;
    if (request_schema(client, setup_deadline_ns) < 0)
        goto fail;

    synchronization_t sync;
    if (initialize_synchronization(client, &sync) < 0) {
        failure = ROADCAST_CLIENT_ERR_MEMORY;
        goto fail;
    }
    failure = ROADCAST_CLIENT_ERR_SNAPSHOT;
    if (synchronize(client, &sync, setup_deadline_ns) < 0) {
        destroy_synchronization(&sync);
        goto fail;
    }
    commit_synchronization(client, &sync);
    destroy_synchronization(&sync);

    int thread_error = pthread_create(&client->reader_thread, NULL,
                                      reader_thread_main, client);
    if (thread_error != 0) {
        failure = ROADCAST_CLIENT_ERR_THREAD;
        goto fail;
    }
    client->reader_started = 1;
    return client;

fail:
    if (error)
        *error = failure;
    roadcast_client_close(client);
    return NULL;
}

void roadcast_client_close(roadcast_client_t *client) {
    if (!client)
        return;
    atomic_store_explicit(&client->stop_requested, true, memory_order_release);
    if (client->fd >= 0)
        shutdown(client->fd, SHUT_RDWR);
    if (client->reader_started)
        pthread_join(client->reader_thread, NULL);
    if (client->fd >= 0)
        close(client->fd);
    pthread_mutex_destroy(&client->cache_lock);
    free(client->payload);
    free(client->frame_scratch);
    free(client->signal_scratch);
    free(client->schema);
    free(client->frames);
    free(client->signals);
    free(client);
}

int roadcast_client_status(roadcast_client_t *client,
                           roadcast_client_status_t *status) {
    if (!client || !status)
        return ROADCAST_CLIENT_ERR_ARGUMENT;
    pthread_mutex_lock(&client->cache_lock);
    *status = (roadcast_client_status_t){
        .hz = client->hz,
        .frame_count = client->frame_count,
        .signal_count = client->signal_count,
        .schema_version = client->schema_version,
        .schema_hash = client->schema_hash,
        .sample_sequence = client->sample_sequence,
        .change_sequence = client->change_sequence,
        .sample_timestamp_ns = client->sample_timestamp_ns,
        .dropped_batches = client->dropped_batches,
        .coalesced_samples = client->coalesced_samples,
        .resynchronizations = client->resynchronizations,
        .effective_hz_millihz = client->effective_hz_millihz,
        .source_state = client->source_state,
        .connected = client->connected,
    };
    pthread_mutex_unlock(&client->cache_lock);
    return ROADCAST_CLIENT_OK;
}

const roadcast_schema_entry_t *
roadcast_client_schema_at(const roadcast_client_t *client, uint32_t index) {
    if (!client || index >= client->signal_count)
        return NULL;
    return &client->schema[index];
}

int32_t roadcast_client_find_signal(const roadcast_client_t *client,
                                    const char *name) {
    if (!client || !name)
        return ROADCAST_CLIENT_ERR_ARGUMENT;
    for (uint32_t i = 0; i < client->signal_count; i++) {
        if (strcmp(client->schema[i].name, name) == 0)
            return (int32_t)i;
    }
    return -1;
}

int32_t
roadcast_client_find_signal_by_stable_id(const roadcast_client_t *client,
                                         uint64_t stable_id) {
    if (!client)
        return ROADCAST_CLIENT_ERR_ARGUMENT;
    for (uint32_t i = 0; i < client->signal_count; i++) {
        if (client->schema[i].stable_id == stable_id)
            return (int32_t)i;
    }
    return -1;
}

int roadcast_client_read_signal(roadcast_client_t *client, uint32_t index,
                                roadcast_signal_value_t *value) {
    return roadcast_client_read_signals(client, &index, 1, value);
}

int roadcast_client_read_signals(roadcast_client_t *client,
                                 const uint32_t *indices, size_t count,
                                 roadcast_signal_value_t *values) {
    if (!client || (!indices && count) || (!values && count))
        return ROADCAST_CLIENT_ERR_ARGUMENT;
    pthread_mutex_lock(&client->cache_lock);
    for (size_t i = 0; i < count; i++) {
        if (indices[i] >= client->signal_count) {
            pthread_mutex_unlock(&client->cache_lock);
            return ROADCAST_CLIENT_ERR_ARGUMENT;
        }
        values[i] = client->signals[indices[i]];
    }
    pthread_mutex_unlock(&client->cache_lock);
    return ROADCAST_CLIENT_OK;
}

int roadcast_client_read_frame(roadcast_client_t *client, uint16_t can_id,
                               roadcast_frame_t *frame) {
    if (!client || !frame)
        return ROADCAST_CLIENT_ERR_ARGUMENT;
    pthread_mutex_lock(&client->cache_lock);
    uint16_t target = (can_id < CAN_ID_LOOKUP_CAP)
                          ? client->can_id_to_index[can_id]
                          : UINT16_MAX;
    if (target < client->frame_count) {
        *frame = client->frames[target];
        pthread_mutex_unlock(&client->cache_lock);
        return ROADCAST_CLIENT_OK;
    }
    pthread_mutex_unlock(&client->cache_lock);
    return ROADCAST_CLIENT_ERR_ARGUMENT;
}

int32_t roadcast_client_read_all(roadcast_client_t *client,
                                 roadcast_signal_value_t *values,
                                 size_t capacity) {
    if (!client || !values || capacity < client->signal_count)
        return ROADCAST_CLIENT_ERR_ARGUMENT;
    pthread_mutex_lock(&client->cache_lock);
    memcpy(values, client->signals,
           client->signal_count * sizeof(*client->signals));
    pthread_mutex_unlock(&client->cache_lock);
    return (int32_t)client->signal_count;
}

int64_t roadcast_client_sample_age_ns(roadcast_client_t *client) {
    if (!client)
        return -1;
    pthread_mutex_lock(&client->cache_lock);
    uint64_t timestamp_ns = client->sample_timestamp_ns;
    int connected = client->connected;
    pthread_mutex_unlock(&client->cache_lock);
    uint64_t now = monotonic_ns();
    if (!connected || !timestamp_ns || timestamp_ns > now)
        return -1;
    uint64_t age = now - timestamp_ns;
    return age > INT64_MAX ? INT64_MAX : (int64_t)age;
}

const char *roadcast_client_error_string(int error) {
    switch (error) {
    case ROADCAST_CLIENT_OK:
        return "ok";
    case ROADCAST_CLIENT_ERR_ARGUMENT:
        return "invalid argument";
    case ROADCAST_CLIENT_ERR_SOCKET:
        return "socket connection failed";
    case ROADCAST_CLIENT_ERR_HANDSHAKE:
        return "protocol handshake failed";
    case ROADCAST_CLIENT_ERR_PROTOCOL:
        return "malformed protocol message";
    case ROADCAST_CLIENT_ERR_SCHEMA:
        return "schema discovery failed";
    case ROADCAST_CLIENT_ERR_SNAPSHOT:
        return "snapshot or subscription failed";
    case ROADCAST_CLIENT_ERR_MEMORY:
        return "native allocation failed";
    case ROADCAST_CLIENT_ERR_THREAD:
        return "reader thread creation failed";
    default:
        return "unknown client error";
    }
}
