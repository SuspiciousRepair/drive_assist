package com.geely.modehelper;

/** A stale screen may detach only its own preview; dead screens expire. */
final class DashcamPreviewLease {
    static final long TIMEOUT_MS = 10_000L;
    private String session;
    private long lastSeen;

    static boolean validSession(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    boolean attach(String owner, long now) {
        if (!validSession(owner)) throw new IllegalArgumentException("Invalid preview session");
        boolean changed = !owner.equals(session);
        session = owner; lastSeen = now;
        return changed;
    }

    boolean detach(String owner) {
        if (!matches(owner)) return false;
        session = null; return true;
    }

    boolean matches(String owner) { return owner != null && owner.equals(session); }
    String session() { return session; }
    boolean expired(long now) { return session != null && now - lastSeen >= TIMEOUT_MS; }
}
