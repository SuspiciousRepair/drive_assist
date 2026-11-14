/* Real process identity/fallback checks, using only disposable child processes. */
#define _GNU_SOURCE
#include <sys/types.h>
#include <sys/uio.h>

#if defined(__linux__) || defined(__ANDROID__)
static ssize_t tracked_vm_readv(pid_t pid, const struct iovec *local,
                                unsigned long local_count,
                                const struct iovec *remote,
                                unsigned long remote_count,
                                unsigned long flags);
#define process_vm_readv tracked_vm_readv
#endif
#include "../src/vhal_source.c"
#undef process_vm_readv

#include <assert.h>
#include <limits.h>
#include <signal.h>
#include <sys/wait.h>
#if defined(__linux__) || defined(__ANDROID__)
#include <sys/prctl.h>
#endif

/* The resolver uses this fixture symbol exactly as it uses an OEM CAN buffer. */
volatile uint64_t MMI_Rx_085 = UINT64_C(0x123456789abcdef0);

#if defined(__linux__) || defined(__ANDROID__)
static unsigned int vm_reads;
static int injected_vm_error;

static ssize_t tracked_vm_readv(pid_t pid, const struct iovec *local,
                                unsigned long local_count,
                                const struct iovec *remote,
                                unsigned long remote_count,
                                unsigned long flags) {
    vm_reads++;
    if (injected_vm_error) {
        errno = injected_vm_error;
        if (injected_vm_error == EINTR)
            injected_vm_error = 0;
        return -1;
    }
    return process_vm_readv(pid, local, local_count, remote, remote_count, flags);
}

static pid_t spawn_fixture(const char *name, const char *mentioned_name) {
    int ready[2];
    assert(pipe(ready) == 0);
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        close(ready[0]);
        char descriptor[32];
        snprintf(descriptor, sizeof(descriptor), "%d", ready[1]);
        execl("/proc/self/exe", name, "--fixture", descriptor,
              mentioned_name, (char *)NULL);
        _exit(127);
    }
    close(ready[1]);
    char byte;
    assert(read(ready[0], &byte, 1) == 1 && byte == 'R');
    close(ready[0]);
    return pid;
}

static void stop_fixture(pid_t pid) {
    assert(kill(pid, SIGTERM) == 0);
    assert(waitpid(pid, NULL, 0) == pid);
}
#endif

int main(int argc, char **argv) {
#if defined(__linux__) || defined(__ANDROID__)
    if (argc >= 3 && (strcmp(argv[1], "--fixture") == 0 ||
                      strcmp(argv[1], "--fixture-changing") == 0)) {
        alarm(30); /* A failed assertion in the parent cannot leave a fixture. */
        if (strcmp(argv[1], "--fixture-changing") == 0) {
            /* Under Yama the daemon is a sibling, not this fixture's parent.
             * Grant only that daemon access to this disposable test process. */
            assert(argc >= 4);
            char *end;
            long reader = strtol(argv[3], &end, 10);
            assert(reader > 0 && reader <= INT_MAX && *end == '\0');
            int permitted = prctl(PR_SET_PTRACER, (unsigned long)reader,
                                   0UL, 0UL, 0UL);
            assert(permitted == 0 || errno == EINVAL); /* No Yama on some kernels. */
        }
        int ready = atoi(argv[2]);
        assert(write(ready, "R", 1) == 1);
        close(ready);
        for (;;) {
            if (strcmp(argv[1], "--fixture-changing") == 0) {
                MMI_Rx_085++;
                usleep(20000);
            } else {
                pause();
            }
        }
    }
    char target[64];
    snprintf(target, sizeof(target), "roadcast-fixture-%d", getpid());
    pid_t decoy = spawn_fixture("unrelated-fixture", target);
    assert(find_pid(target) == -1); /* Arguments are not executable identity. */
    pid_t fixture = spawn_fixture(target, "unused");

    roadcast_vhal_source_t source;
    assert(roadcast_vhal_open(&source, target) == 0);
    assert(source.pid == fixture && source.resolved_count == 1);
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    assert(roadcast_vhal_read(&source, frames) == 0 && vm_reads == 1);
    uint64_t value;
    memcpy(&value, frames[0].data, sizeof(value));
    assert(value == MMI_Rx_085);

    injected_vm_error = EPERM;
    assert(roadcast_vhal_read(&source, frames) == 1 && vm_reads == 2);
    assert(source.vm_readv_retry_ns > 0 &&
           source.vm_readv_retry_ns < UINT64_MAX);
    injected_vm_error = 0;
    assert(roadcast_vhal_read(&source, frames) == 1 && vm_reads == 2);
    source.vm_readv_retry_ns = 0; /* Advance the retry deadline without sleeping. */
    assert(roadcast_vhal_read(&source, frames) == 0 && vm_reads == 3);

    injected_vm_error = ENOSYS;
    assert(roadcast_vhal_read(&source, frames) == 1 && vm_reads == 4);
    assert(source.vm_readv_retry_ns == UINT64_MAX);
    injected_vm_error = 0;
    assert(roadcast_vhal_read(&source, frames) == 1);
    assert(vm_reads == 4);
    memcpy(&value, frames[0].data, sizeof(value));
    assert(value == MMI_Rx_085); /* Cached fallback still reads the source. */

    source.start_time_ticks++;
    assert(roadcast_vhal_read(&source, frames) == -1 && errno == ESRCH);
    source.start_time_ticks--;
    assert(roadcast_vhal_read(&source, frames) == 1);

    /* Preserve memory-read errors even when the later identity check succeeds. */
    uint64_t address = source.addresses[0];
    source.addresses[0] = UINT64_C(1);
    assert(roadcast_vhal_read(&source, frames) == -1 && errno == EIO);
    source.addresses[0] = address;
    source.vm_readv_retry_ns = 0;
    injected_vm_error = EINTR;
    assert(roadcast_vhal_read(&source, frames) == 0 && vm_reads == 6);
    stop_fixture(fixture);

    /* Simulate numeric PID reuse: readable new process, old identity handle. */
    source.pid = getpid();
    source.addresses[0] = (uint64_t)(uintptr_t)&MMI_Rx_085;
    source.remote_iov[0].iov_base = (void *)(uintptr_t)&MMI_Rx_085;
    source.vm_readv_retry_ns = 0;
    assert(roadcast_vhal_read(&source, frames) == -1 && errno == ESRCH);
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++)
        assert(frames[i].state == ROADCAST_OBSERVATION_UNAVAILABLE);
    roadcast_vhal_close(&source);
    assert(source.mem_fd == -1 && source.identity_fd == -1);
    assert(find_pid(target) == -1);

    fixture = spawn_fixture(target, "unused");
    assert(roadcast_vhal_open(&source, target) == 0);
    assert(source.vm_readv_retry_ns == 0 && source.pid == fixture);
    assert(roadcast_vhal_read(&source, frames) == 0);
    roadcast_vhal_close(&source);
    stop_fixture(fixture);
    stop_fixture(decoy);
    puts("vhal source identity/fallback tests passed");
#else
    (void)argc;
    (void)argv;
    puts("vhal source process tests require Linux/Android /proc (skipped)");
#endif
    return 0;
}
