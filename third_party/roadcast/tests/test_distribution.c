/* Exercise canonical-state updates without opening a client connection. */
#define main roadcast_daemon_main
#include "../src/roadcastd.c"
#undef main

#include <assert.h>

int main(void) {
    roadcast_server_t *server = calloc(1, sizeof(*server));
    assert(server);
    assert(sampler_init(&server->sampler, NULL, 60, 1, "unused") == 0);
    sampler_read_latest(&server->sampler, server->frames,
                        &server->sample_sequence, &server->sample_timestamp_ns);
    roadcast_decode_signals(server->frames, server->signals);

    for (uint64_t sequence = 1; sequence <= 3; sequence++) {
        /* Neither setup sessions nor sessions awaiting resync consume deltas. */
        server->clients[0].active = sequence >= 2;
        server->clients[1].active = sequence >= 3;
        server->clients[1].subscribed = sequence >= 3;
        server->clients[1].needs_resync = sequence >= 3;

        roadcast_frame_t sampled[ROADCAST_FRAME_COUNT];
        initialize_fake_frames(sampled);
        update_fake_frames(sampled, sequence, 1);
        uint64_t timestamp_ns = sequence * UINT64_C(1000000000);
        merge_sampled_frames(server->sampler.published, sampled,
                             timestamp_ns, 0);
        server->sampler.published_sequence = sequence;
        server->sampler.published_timestamp_ns = timestamp_ns;
        distribute_latest_sample(server);

        assert(server->sample_sequence == sequence);
        assert(server->change_sequence == sequence);
        assert(server->sample_timestamp_ns == timestamp_ns);
        assert(server->stats.ticks == sequence);
        assert(server->stats.serialized_chunks == 0);
        for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
            assert(server->frames[i].state == ROADCAST_OBSERVATION_VALID);
            assert(server->frames[i].last_change_ns == timestamp_ns);
        }
        roadcast_signal_value_t expected[ROADCAST_SIGNAL_COUNT];
        roadcast_decode_signals(server->frames, expected);
        for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
            assert(server->signals[i].raw == expected[i].raw);
            assert(server->signals[i].physical == expected[i].physical);
            assert(server->signals[i].state == expected[i].state);
        }
    }
    sampler_destroy(&server->sampler);
    free(server);
    puts("distribution tests passed");
    return 0;
}
