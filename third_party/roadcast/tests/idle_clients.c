#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int connect_socket(const char *socket_path) {
    size_t path_length = strlen(socket_path);
    if (!path_length ||
        path_length >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        errno = EINVAL;
        return -1;
    }
    int descriptor = socket(AF_UNIX, SOCK_STREAM, 0);
    if (descriptor < 0)
        return -1;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    memcpy(address.sun_path, socket_path, path_length + 1);
    socklen_t address_length =
        (socklen_t)(offsetof(struct sockaddr_un, sun_path) + path_length + 1);
    if (connect(descriptor, (const struct sockaddr *)&address, address_length) <
        0) {
        close(descriptor);
        return -1;
    }
    return descriptor;
}

static int parse_positive_int(const char *value, int *output) {
    char *end = NULL;
    long parsed = strtol(value, &end, 10);
    if (!value[0] || !end || *end || parsed < 1 || parsed > 1024)
        return -1;
    *output = (int)parsed;
    return 0;
}

int main(int argc, char **argv) {
    int connection_count;
    int hold_seconds;
    if (argc != 4 || parse_positive_int(argv[2], &connection_count) < 0 ||
        parse_positive_int(argv[3], &hold_seconds) < 0) {
        fprintf(stderr, "Usage: %s socket-path count hold-seconds\n", argv[0]);
        return 2;
    }

    int *descriptors = calloc((size_t)connection_count, sizeof(*descriptors));
    if (!descriptors)
        return 1;
    for (int i = 0; i < connection_count; i++) {
        descriptors[i] = connect_socket(argv[1]);
        if (descriptors[i] < 0) {
            fprintf(stderr, "Connection %d failed: %s\n", i, strerror(errno));
            for (int j = 0; j < i; j++)
                close(descriptors[j]);
            free(descriptors);
            return 1;
        }
    }

    printf("idle clients ready: %d\n", connection_count);
    fflush(stdout);
    sleep((unsigned int)hold_seconds);
    for (int i = 0; i < connection_count; i++)
        close(descriptors[i]);
    free(descriptors);
    return 0;
}
