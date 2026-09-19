package com.geely.modehelper;

/** Two bounded shared textures: capture never waits for a slow preview consumer. */
final class PreviewFrameSlots {
    private static final int FREE = 0, WRITING = 1, READY = 2, READING = 3;
    private final int[] slots = new int[2];

    synchronized int beginWrite() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == FREE) { slots[i] = WRITING; return i; }
        }
        return -1;
    }

    synchronized void publish(int slot) {
        if (slots[slot] != WRITING) throw new IllegalStateException("Not a writable frame");
        for (int i = 0; i < slots.length; i++) if (slots[i] == READY) slots[i] = FREE;
        slots[slot] = READY;
    }

    synchronized int acquireLatest() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == READY) { slots[i] = READING; return i; }
        }
        return -1;
    }

    synchronized void release(int slot) { slots[slot] = FREE; }
}
