package com.geely.modehelper;

/** Serial admission for the sole EVS/encoder owner, including stop/start races. */
final class DashcamRunState {
    private boolean workerActive;
    private boolean recording;
    private boolean restartRequested;

    /** True grants a new worker ownership; otherwise an On may be queued. */
    synchronized boolean requestStart() {
        if (recording) return false;
        if (workerActive) {
            restartRequested = true;
            return false;
        }
        workerActive = true;
        recording = true;
        return true;
    }

    synchronized void requestStop() {
        recording = false;
        restartRequested = false;
    }

    synchronized boolean isRecording() { return recording; }

    /** Failure or stop enters teardown, while the existing worker still owns EVS. */
    synchronized void workerStopping() { recording = false; }

    /** Call only after releasing the previous codec, input surface and GPS. */
    synchronized boolean workerFinished() {
        workerActive = false;
        if (!restartRequested) return false;
        restartRequested = false;
        workerActive = true;
        recording = true;
        return true;
    }
}
