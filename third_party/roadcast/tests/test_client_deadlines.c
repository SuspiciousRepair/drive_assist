#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "roadcast_client.h"

static uint64_t monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void test_connect_timeout_unresponsive(void) {
    /* Create a listening unix socket that never accepts */
    const char *socket_name = "/tmp/roadcast_test_unresponsive.sock";
    unlink(socket_name);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(sfd >= 0);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_name, sizeof(addr.sun_path) - 1);
    assert(bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    assert(listen(sfd, 0) == 0); /* backlog 0 */

    /* Accept in background but never send anything */
    int error = 0;
    uint64_t start = monotonic_ns();
    roadcast_client_t *client = roadcast_client_connect(socket_name, &error);
    uint64_t elapsed_ms = (monotonic_ns() - start) / 1000000ULL;

    assert(client == NULL);
    /* Should fail with handshake or socket error within deadline */
    assert(error == ROADCAST_CLIENT_ERR_HANDSHAKE || error == ROADCAST_CLIENT_ERR_SOCKET);
    assert(elapsed_ms >= 2500 && elapsed_ms <= 6000);

    close(sfd);
    unlink(socket_name);
}

static void test_slow_trickle_deadline(void) {
    /* Socket where server accepts, sends 1 byte of header every 800ms.
     * Total header is 32 bytes. At 800ms/byte, 32 bytes would take 25s.
     * The client read timeout is 3000ms. It MUST time out after ~3000ms,
     * not reset timeout on every received byte! */
    const char *socket_name = "/tmp/roadcast_test_trickle.sock";
    unlink(socket_name);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(sfd >= 0);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_name, sizeof(addr.sun_path) - 1);
    assert(bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    assert(listen(sfd, 1) == 0);

    /* Background thread to accept and trickle bytes */
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd >= 0) {
            uint8_t dummy = 0;
            for (int i = 0; i < 20; i++) {
                usleep(700000); /* 700ms */
                if (write(cfd, &dummy, 1) <= 0)
                    break;
            }
            close(cfd);
        }
        close(sfd);
        _exit(0);
    }

    int error = 0;
    uint64_t start = monotonic_ns();
    roadcast_client_t *client = roadcast_client_connect(socket_name, &error);
    uint64_t elapsed_ms = (monotonic_ns() - start) / 1000000ULL;

    assert(client == NULL);
    assert(error == ROADCAST_CLIENT_ERR_HANDSHAKE);
    /* Client must time out around 3000ms (allow 2700 - 5000ms for scheduling) */
    assert(elapsed_ms >= 2700 && elapsed_ms <= 5000);

    close(sfd);
    unlink(socket_name);
}

int main(void) {
    test_connect_timeout_unresponsive();
    test_slow_trickle_deadline();
    puts("client deadline tests passed");
    return 0;
}
