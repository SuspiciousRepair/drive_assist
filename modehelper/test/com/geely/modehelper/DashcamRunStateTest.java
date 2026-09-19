package com.geely.modehelper;

/** Deterministic lifecycle interleavings without requiring a vehicle camera. */
public final class DashcamRunStateTest {
    public static void main(String[] args) {
        DashcamRunState state = new DashcamRunState();
        check(state.requestStart() && state.isRecording(), "first On starts recording");
        check(!state.requestStart(), "repeated On cannot create a second consumer");
        state.requestStop();
        check(!state.isRecording(), "Off stops current drain loop");
        check(!state.requestStart() && !state.isRecording(), "Off-On queues until cleanup finishes");
        check(!state.requestStart(), "repeated On queues only one replacement");
        state.workerStopping();
        check(state.workerFinished() && state.isRecording(), "On is honored after prior owner releases resources");
        check(!state.requestStart(), "new owner remains single");

        state.requestStop();
        check(!state.requestStart(), "second Off-On queues");
        state.requestStop();
        state.workerStopping();
        check(!state.workerFinished() && !state.isRecording(), "Off-On-Off cancels pending restart");
        check(state.requestStart(), "a later explicit On starts normally");

        state.workerStopping();
        check(!state.workerFinished() && !state.isRecording(), "failure does not enter an automatic retry loop");
        check(state.requestStart(), "explicit retry after failure works");
        state.workerStopping();
        check(!state.requestStart(), "explicit retry during failed-run cleanup queues");
        check(state.workerFinished() && state.isRecording(), "queued manual retry waits for cleanup");

        state.requestStop();
        state.workerStopping();
        check(!state.workerFinished(), "normal stop remains stopped");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
