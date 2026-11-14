#define _GNU_SOURCE

#include <errno.h>
#include <inttypes.h>
#include <poll.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "roadcast_frames.h"
#include "roadcast_protocol.h"

#define DEFAULT_SOCKET "@roadcast"
#define DEFAULT_SECONDS 10
#define READ_TIMEOUT_MS 3000
#define SCHEMA_CHUNK_ENTRY_CAP 64
#define SIGNAL_CHUNK_UPDATE_CAP 128

typedef struct {
    roadcast_schema_entry_t *schema;
    roadcast_signal_value_t *signals;
    uint8_t *matches;
    uint32_t signal_count;
    const char *filter;
} client_catalog_t;

static uint64_t monotonic_ns(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (uint64_t)time.tv_sec * 1000000000ull + (uint64_t)time.tv_nsec;
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

static int connect_socket(const char *socket_name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    struct sockaddr_un address;
    socklen_t length = fill_unix_address(&address, socket_name);
    if (!length || connect(fd, (const struct sockaddr *)&address, length) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int write_all(int fd, const uint8_t *data, size_t length) {
    while (length) {
        ssize_t written = send(fd, data, length, MSG_NOSIGNAL);
        if (written > 0) {
            data += (size_t)written;
            length -= (size_t)written;
            continue;
        }
        if (written < 0 && errno == EINTR)
            continue;
        return -1;
    }
    return 0;
}

static int send_message(int fd, uint16_t type, const uint8_t *payload,
                        size_t payload_length) {
    if (payload_length > ROADCAST_MAX_PAYLOAD)
        return -1;
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
    if (write_all(fd, header_bytes, sizeof(header_bytes)) < 0)
        return -1;
    if (payload_length && write_all(fd, payload, payload_length) < 0)
        return -1;
    return 0;
}

static int read_exact(int fd, uint8_t *out, size_t length, int timeout_ms) {
    while (length) {
        struct pollfd descriptor = {
            .fd = fd,
            .events = POLLIN,
            .revents = 0,
        };
        int result = poll(&descriptor, 1, timeout_ms);
        if (result <= 0) {
            if (result < 0 && errno == EINTR)
                continue;
            return -1;
        }
        if (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL))
            return -1;
        ssize_t count = recv(fd, out, length, 0);
        if (count > 0) {
            out += (size_t)count;
            length -= (size_t)count;
            continue;
        }
        if (count < 0 && errno == EINTR)
            continue;
        return -1;
    }
    return 0;
}

static int read_message(int fd, roadcast_header_t *header, uint8_t *payload,
                        size_t payload_capacity, int timeout_ms) {
    uint8_t header_bytes[ROADCAST_HEADER_SIZE];
    if (read_exact(fd, header_bytes, sizeof(header_bytes), timeout_ms) < 0 ||
        roadcast_decode_header(header_bytes, header) < 0 ||
        header->version != ROADCAST_PROTOCOL_VERSION ||
        header->payload_bytes > payload_capacity)
        return -1;
    if (header->payload_bytes &&
        read_exact(fd, payload, header->payload_bytes, timeout_ms) < 0)
        return -1;
    return 0;
}

static int request_schema(int fd, roadcast_header_t *header, uint8_t *payload,
                          size_t payload_capacity, client_catalog_t *catalog,
                          uint32_t expected_version, uint64_t expected_hash) {
    uint32_t next_index = 0;
    while (next_index < catalog->signal_count) {
        uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
        roadcast_encode_index_request(request, next_index);
        if (send_message(fd, ROADCAST_MSG_GET_SCHEMA, request,
                         sizeof(request)) < 0 ||
            read_message(fd, header, payload, payload_capacity,
                         READ_TIMEOUT_MS) < 0 ||
            header->type != ROADCAST_MSG_SCHEMA_CHUNK)
            return -1;

        roadcast_schema_entry_t entries[SCHEMA_CHUNK_ENTRY_CAP];
        uint32_t schema_version;
        uint32_t total_count;
        uint32_t start_index;
        uint64_t schema_hash;
        size_t entry_count;
        if (roadcast_decode_schema_chunk(
                payload, header->payload_bytes, &schema_version, &total_count,
                &start_index, &schema_hash, entries, SCHEMA_CHUNK_ENTRY_CAP,
                &entry_count) < 0 ||
            schema_version != expected_version ||
            schema_hash != expected_hash ||
            total_count != catalog->signal_count || start_index != next_index ||
            entry_count == 0)
            return -1;

        for (size_t i = 0; i < entry_count; i++) {
            catalog->schema[next_index + i] = entries[i];
            if (catalog->filter &&
                strstr(entries[i].name, catalog->filter) != NULL) {
                catalog->matches[next_index + i] = 1;
                printf("schema-entry: index=%u id=%016" PRIx64
                       " can=0x%03x name=%s width=%u scale=%.9g "
                       "offset=%.9g unit=%s calibrated=%u\n",
                       entries[i].index, entries[i].stable_id,
                       entries[i].can_id, entries[i].name, entries[i].width,
                       entries[i].scale, entries[i].offset,
                       entries[i].unit[0] ? entries[i].unit : "-",
                       !!(entries[i].flags & ROADCAST_SCHEMA_FLAG_CALIBRATED));
            }
        }
        next_index += (uint32_t)entry_count;
    }

    printf("schema: version=%u signals=%u hash=%016" PRIx64 "\n",
           expected_version, catalog->signal_count, expected_hash);
    return 0;
}

static int request_frame_snapshot(int fd, roadcast_header_t *header,
                                  uint8_t *payload, size_t payload_capacity,
                                  uint64_t *sample_sequence,
                                  uint64_t *change_sequence) {
    uint32_t next_index = 0;
    while (next_index < ROADCAST_FRAME_COUNT) {
        uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
        roadcast_encode_index_request(request, next_index);
        if (send_message(fd, ROADCAST_MSG_GET_SNAPSHOT, request,
                         sizeof(request)) < 0 ||
            read_message(fd, header, payload, payload_capacity,
                         READ_TIMEOUT_MS) < 0 ||
            header->type != ROADCAST_MSG_SNAPSHOT)
            return -1;

        roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
        uint64_t page_sample_sequence;
        uint32_t total_count;
        uint32_t start_index;
        size_t frame_count;
        if (roadcast_decode_frame_batch(
                payload, header->payload_bytes, &page_sample_sequence,
                &total_count, &start_index, frames, ROADCAST_FRAME_COUNT,
                &frame_count) < 0 ||
            total_count != ROADCAST_FRAME_COUNT || start_index != next_index ||
            frame_count == 0 ||
            (next_index && (page_sample_sequence != *sample_sequence ||
                            header->sequence != *change_sequence)))
            return -1;
        if (!next_index) {
            *sample_sequence = page_sample_sequence;
            *change_sequence = header->sequence;
        }
        next_index += (uint32_t)frame_count;
    }
    return 0;
}

static void print_signal_value(const client_catalog_t *catalog, uint32_t index,
                               const char *prefix) {
    const roadcast_schema_entry_t *entry = &catalog->schema[index];
    const roadcast_signal_value_t *value = &catalog->signals[index];
    printf("%s: index=%u name=%s raw=0x%016" PRIx64
           " value=%.9g unit=%s state=%u calibrated=%u first_ns=%" PRIu64
           " changed_ns=%" PRIu64 "\n",
           prefix, index, entry->name, value->raw, value->physical,
           entry->unit[0] ? entry->unit : "-", value->state, value->calibrated,
           value->first_observed_ns, value->last_change_ns);
}

static int request_signal_snapshot(int fd, roadcast_header_t *header,
                                   uint8_t *payload, size_t payload_capacity,
                                   client_catalog_t *catalog,
                                   uint64_t expected_sample_sequence,
                                   uint64_t expected_change_sequence) {
    uint32_t next_index = 0;
    while (next_index < catalog->signal_count) {
        uint8_t request[ROADCAST_SCHEMA_REQUEST_SIZE];
        roadcast_encode_index_request(request, next_index);
        if (send_message(fd, ROADCAST_MSG_GET_SIGNAL_SNAPSHOT, request,
                         sizeof(request)) < 0 ||
            read_message(fd, header, payload, payload_capacity,
                         READ_TIMEOUT_MS) < 0 ||
            header->type != ROADCAST_MSG_SIGNAL_SNAPSHOT_CHUNK ||
            header->sequence != expected_change_sequence)
            return -1;

        roadcast_signal_update_t updates[SIGNAL_CHUNK_UPDATE_CAP];
        uint64_t sample_sequence;
        uint32_t total_count;
        uint32_t start_index;
        size_t update_count;
        if (roadcast_decode_signal_batch(
                payload, header->payload_bytes, &sample_sequence, &total_count,
                &start_index, updates, SIGNAL_CHUNK_UPDATE_CAP,
                &update_count) < 0 ||
            sample_sequence != expected_sample_sequence ||
            total_count != catalog->signal_count || start_index != next_index ||
            update_count == 0)
            return -1;
        for (size_t i = 0; i < update_count; i++) {
            if (updates[i].index != next_index + i ||
                updates[i].index >= catalog->signal_count)
                return -1;
            catalog->signals[updates[i].index] = updates[i].value;
        }
        next_index += (uint32_t)update_count;
    }

    printf("snapshot: frames=%zu signals=%u sample_seq=%" PRIu64
           " change_seq=%" PRIu64 "\n",
           (size_t)ROADCAST_FRAME_COUNT, catalog->signal_count,
           expected_sample_sequence, expected_change_sequence);
    if (catalog->filter) {
        for (uint32_t i = 0; i < catalog->signal_count; i++) {
            if (catalog->matches[i])
                print_signal_value(catalog, i, "signal");
        }
    }
    return 0;
}

static int request_complete_snapshot(int fd, roadcast_header_t *header,
                                     uint8_t *payload, size_t payload_capacity,
                                     client_catalog_t *catalog,
                                     uint64_t *sample_sequence,
                                     uint64_t *change_sequence) {
    return request_frame_snapshot(fd, header, payload, payload_capacity,
                                  sample_sequence, change_sequence) < 0 ||
                   request_signal_snapshot(
                       fd, header, payload, payload_capacity, catalog,
                       *sample_sequence, *change_sequence) < 0
               ? -1
               : 0;
}

static int complete_subscription(int fd, roadcast_header_t *header,
                                 uint8_t *payload, size_t payload_capacity,
                                 client_catalog_t *catalog,
                                 uint64_t *sample_sequence,
                                 uint64_t *change_sequence) {
    if (send_message(fd, ROADCAST_MSG_SUBSCRIBE_ALL, NULL, 0) < 0)
        return -1;

    for (;;) {
        if (read_message(fd, header, payload, payload_capacity,
                         READ_TIMEOUT_MS) < 0)
            return -1;
        if (header->type == ROADCAST_MSG_SUBSCRIBED) {
            *change_sequence = header->sequence;
            return 0;
        }
        if (header->type == ROADCAST_MSG_UPDATE_BATCH) {
            roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
            uint32_t total_count;
            uint32_t start_index;
            size_t count;
            if (roadcast_decode_frame_batch(payload, header->payload_bytes,
                                            sample_sequence, &total_count,
                                            &start_index, frames,
                                            ROADCAST_FRAME_COUNT, &count) < 0 ||
                total_count != ROADCAST_FRAME_COUNT ||
                start_index != UINT32_MAX)
                return -1;
            continue;
        }
        if (header->type == ROADCAST_MSG_SIGNAL_UPDATE_BATCH) {
            roadcast_signal_update_t updates[SIGNAL_CHUNK_UPDATE_CAP];
            uint32_t total_count;
            uint32_t start_index;
            size_t count;
            if (roadcast_decode_signal_batch(
                    payload, header->payload_bytes, sample_sequence,
                    &total_count, &start_index, updates,
                    SIGNAL_CHUNK_UPDATE_CAP, &count) < 0 ||
                total_count != catalog->signal_count ||
                start_index != UINT32_MAX)
                return -1;
            for (size_t i = 0; i < count; i++) {
                if (updates[i].index >= catalog->signal_count)
                    return -1;
                catalog->signals[updates[i].index] = updates[i].value;
            }
            continue;
        }
        return -1;
    }
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
            "Usage: %s [--socket @name|path] [--seconds 1..3600] "
            "[--signal substring] [--stall-after-subscribe 1..3600] "
            "[--pause-after-subscribe 1..3600] "
            "[--delay-before-subscribe 1..3600]\n",
            program);
}

static void observe_change_sequence(uint64_t sequence, uint64_t *last_sequence,
                                    uint64_t *gap_count) {
    if (sequence == *last_sequence)
        return;
    if (*last_sequence && sequence != *last_sequence + 1)
        (*gap_count)++;
    *last_sequence = sequence;
}

int main(int argc, char **argv) {
    const char *socket_name = DEFAULT_SOCKET;
    const char *signal_filter = NULL;
    int seconds = DEFAULT_SECONDS;
    int stall_seconds = 0;
    int pause_seconds = 0;
    int subscribe_delay_seconds = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--socket") && i + 1 < argc) {
            socket_name = argv[++i];
        } else if (!strcmp(argv[i], "--seconds") && i + 1 < argc) {
            if (parse_positive_int(argv[++i], 1, 3600, &seconds) < 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--signal") && i + 1 < argc) {
            signal_filter = argv[++i];
        } else if (!strcmp(argv[i], "--stall-after-subscribe") &&
                   i + 1 < argc) {
            if (parse_positive_int(argv[++i], 1, 3600, &stall_seconds) < 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--pause-after-subscribe") &&
                   i + 1 < argc) {
            if (parse_positive_int(argv[++i], 1, 3600, &pause_seconds) < 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--delay-before-subscribe") &&
                   i + 1 < argc) {
            if (parse_positive_int(argv[++i], 1, 3600,
                                   &subscribe_delay_seconds) < 0) {
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
    if (stall_seconds && pause_seconds) {
        usage(argv[0]);
        return 2;
    }

    int fd = connect_socket(socket_name);
    if (fd < 0) {
        fprintf(stderr, "Failed to connect to %s: %s\n", socket_name,
                strerror(errno));
        return 1;
    }

    uint8_t *payload = malloc(ROADCAST_MAX_PAYLOAD);
    if (!payload) {
        close(fd);
        return 1;
    }
    roadcast_header_t header;
    uint8_t hello[8];
    size_t hello_length = roadcast_encode_hello(hello);
    if (send_message(fd, ROADCAST_MSG_HELLO, hello, hello_length) < 0 ||
        read_message(fd, &header, payload, ROADCAST_MAX_PAYLOAD,
                     READ_TIMEOUT_MS) < 0 ||
        header.type != ROADCAST_MSG_WELCOME) {
        fprintf(stderr, "HELLO failed\n");
        free(payload);
        close(fd);
        return 1;
    }

    uint32_t hz;
    uint32_t frame_count;
    uint32_t signal_count;
    uint32_t max_request_payload;
    uint32_t max_response_payload;
    uint32_t capabilities;
    uint32_t schema_version;
    uint64_t schema_hash;
    if (roadcast_decode_welcome(
            payload, header.payload_bytes, &hz, &frame_count, &signal_count,
            &max_request_payload, &max_response_payload, &capabilities,
            &schema_version, &schema_hash) < 0 ||
        frame_count != ROADCAST_FRAME_COUNT ||
        !(capabilities & ROADCAST_CAP_RAW_CAN) ||
        !(capabilities & ROADCAST_CAP_DECODED_CAN)) {
        fprintf(stderr, "Malformed or unsupported WELCOME\n");
        free(payload);
        close(fd);
        return 1;
    }
    printf("welcome: protocol=%u hz=%u frames=%u signals=%u max_request=%u "
           "max_response=%u caps=0x%x\n",
           ROADCAST_PROTOCOL_VERSION, hz, frame_count, signal_count,
           max_request_payload, max_response_payload, capabilities);

    client_catalog_t catalog = {
        .schema = calloc(signal_count, sizeof(*catalog.schema)),
        .signals = calloc(signal_count, sizeof(*catalog.signals)),
        .matches = calloc(signal_count, sizeof(*catalog.matches)),
        .signal_count = signal_count,
        .filter = signal_filter,
    };
    if (!catalog.schema || !catalog.signals || !catalog.matches ||
        request_schema(fd, &header, payload, ROADCAST_MAX_PAYLOAD, &catalog,
                       schema_version, schema_hash) < 0) {
        fprintf(stderr, "Schema discovery failed\n");
        free(catalog.schema);
        free(catalog.signals);
        free(catalog.matches);
        free(payload);
        close(fd);
        return 1;
    }

    uint64_t sample_sequence;
    uint64_t change_sequence;
    if (request_complete_snapshot(fd, &header, payload, ROADCAST_MAX_PAYLOAD,
                                  &catalog, &sample_sequence,
                                  &change_sequence) < 0) {
        fprintf(stderr, "Snapshot or subscription failed\n");
        free(catalog.schema);
        free(catalog.signals);
        free(catalog.matches);
        free(payload);
        close(fd);
        return 1;
    }
    if (subscribe_delay_seconds)
        sleep((unsigned int)subscribe_delay_seconds);
    if (complete_subscription(fd, &header, payload, ROADCAST_MAX_PAYLOAD,
                              &catalog, &sample_sequence,
                              &change_sequence) < 0) {
        fprintf(stderr, "Snapshot or subscription failed\n");
        free(catalog.schema);
        free(catalog.signals);
        free(catalog.matches);
        free(payload);
        close(fd);
        return 1;
    }

    if (stall_seconds) {
        printf("stalling: seconds=%d\n", stall_seconds);
        sleep((unsigned int)stall_seconds);
        free(catalog.schema);
        free(catalog.signals);
        free(catalog.matches);
        free(payload);
        close(fd);
        return 0;
    }
    if (pause_seconds) {
        printf("pausing: seconds=%d\n", pause_seconds);
        sleep((unsigned int)pause_seconds);
    }

    uint64_t started = monotonic_ns();
    uint64_t report_started = started;
    uint64_t frame_batches = 0;
    uint64_t signal_batches = 0;
    uint64_t changed_frames = 0;
    uint64_t changed_signals = 0;
    uint64_t sequence_gaps = 0;
    uint64_t resyncs = 0;
    uint64_t last_sample_sequence = sample_sequence;
    uint64_t last_change_sequence = change_sequence;
    while (monotonic_ns() - started < (uint64_t)seconds * 1000000000ull) {
        if (read_message(fd, &header, payload, ROADCAST_MAX_PAYLOAD,
                         READ_TIMEOUT_MS) < 0) {
            fprintf(stderr, "Read failed or heartbeat timed out\n");
            goto fail;
        }

        if (header.type == ROADCAST_MSG_UPDATE_BATCH) {
            roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
            uint32_t total_count;
            uint32_t start_index;
            size_t count;
            if (roadcast_decode_frame_batch(payload, header.payload_bytes,
                                            &sample_sequence, &total_count,
                                            &start_index, frames,
                                            ROADCAST_FRAME_COUNT, &count) < 0) {
                fprintf(stderr, "Malformed UPDATE_BATCH\n");
                goto fail;
            }
            if (total_count != ROADCAST_FRAME_COUNT ||
                start_index != UINT32_MAX) {
                fprintf(stderr, "Invalid UPDATE_BATCH page metadata\n");
                goto fail;
            }
            observe_change_sequence(header.sequence, &last_change_sequence,
                                    &sequence_gaps);
            last_sample_sequence = sample_sequence;
            frame_batches++;
            changed_frames += count;
        } else if (header.type == ROADCAST_MSG_SIGNAL_UPDATE_BATCH) {
            roadcast_signal_update_t updates[SIGNAL_CHUNK_UPDATE_CAP];
            uint32_t total_count;
            uint32_t start_index;
            size_t count;
            if (roadcast_decode_signal_batch(
                    payload, header.payload_bytes, &sample_sequence,
                    &total_count, &start_index, updates,
                    SIGNAL_CHUNK_UPDATE_CAP, &count) < 0) {
                fprintf(stderr, "Malformed SIGNAL_UPDATE_BATCH\n");
                goto fail;
            }
            if (total_count != catalog.signal_count ||
                start_index != UINT32_MAX) {
                fprintf(stderr, "Invalid SIGNAL_UPDATE_BATCH page metadata\n");
                goto fail;
            }
            observe_change_sequence(header.sequence, &last_change_sequence,
                                    &sequence_gaps);
            last_sample_sequence = sample_sequence;
            signal_batches++;
            changed_signals += count;
            for (size_t i = 0; i < count; i++) {
                uint32_t index = updates[i].index;
                if (index >= catalog.signal_count) {
                    fprintf(stderr, "Signal index outside schema\n");
                    goto fail;
                }
                catalog.signals[index] = updates[i].value;
                if (catalog.matches[index])
                    print_signal_value(&catalog, index, "update");
            }
        } else if (header.type == ROADCAST_MSG_HEARTBEAT) {
            uint64_t heartbeat_change_sequence;
            uint64_t dropped_batches;
            uint64_t coalesced_samples;
            uint32_t effective_hz_millihz;
            uint8_t source_state;
            if (roadcast_decode_heartbeat(
                    payload, header.payload_bytes, &sample_sequence,
                    &heartbeat_change_sequence, &dropped_batches,
                    &coalesced_samples, &effective_hz_millihz,
                    &source_state) < 0) {
                fprintf(stderr, "Malformed HEARTBEAT\n");
                goto fail;
            }
            if (heartbeat_change_sequence != header.sequence) {
                fprintf(stderr, "Inconsistent HEARTBEAT sequence\n");
                goto fail;
            }
            observe_change_sequence(heartbeat_change_sequence,
                                    &last_change_sequence, &sequence_gaps);
            last_sample_sequence = sample_sequence;
        } else if (header.type == ROADCAST_MSG_RESYNC_REQUIRED) {
            if (request_complete_snapshot(
                    fd, &header, payload, ROADCAST_MAX_PAYLOAD, &catalog,
                    &sample_sequence, &change_sequence) < 0 ||
                complete_subscription(fd, &header, payload,
                                      ROADCAST_MAX_PAYLOAD, &catalog,
                                      &sample_sequence, &change_sequence) < 0) {
                fprintf(stderr, "Resynchronization failed\n");
                goto fail;
            }
            last_sample_sequence = sample_sequence;
            last_change_sequence = change_sequence;
            resyncs++;
        } else {
            fprintf(stderr, "Unexpected message type %u\n", header.type);
            goto fail;
        }

        uint64_t now = monotonic_ns();
        if (now - report_started >= 1000000000ull) {
            double elapsed = (double)(now - report_started) / 1e9;
            printf("stream: frame_batches=%.1f/s signal_batches=%.1f/s "
                   "frames=%.1f/s signals=%.1f/s sample_seq=%" PRIu64
                   " gaps=%" PRIu64 " resyncs=%" PRIu64 "\n",
                   frame_batches / elapsed, signal_batches / elapsed,
                   changed_frames / elapsed, changed_signals / elapsed,
                   last_sample_sequence, sequence_gaps, resyncs);
            report_started = now;
            frame_batches = 0;
            signal_batches = 0;
            changed_frames = 0;
            changed_signals = 0;
        }
    }

    printf("summary: gaps=%" PRIu64 " resyncs=%" PRIu64 "\n", sequence_gaps,
           resyncs);
    free(catalog.schema);
    free(catalog.signals);
    free(catalog.matches);
    free(payload);
    close(fd);
    return 0;

fail:
    free(catalog.schema);
    free(catalog.signals);
    free(catalog.matches);
    free(payload);
    close(fd);
    return 1;
}
