package com.example.toto_app.falls;

public final class FallSignals {
    private FallSignals(){}

    public static final String ACTION_FALL_DETECTED = "com.example.toto_app.ACTION_FALL_DETECTED";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_USER_NAME = "user_name";

    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long COOLDOWN_MS = 8000L;
    private static volatile long lastActivatedAt = 0L;

    public static boolean tryActivate() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (ACTIVE.get()) return false;
        if ((now - lastActivatedAt) < COOLDOWN_MS) return false;
        boolean ok = ACTIVE.compareAndSet(false, true);
        if (ok) lastActivatedAt = now;
        return ok;
    }
    public static boolean isActive() { return ACTIVE.get(); }
    public static void clear() { ACTIVE.set(false); /* cooldown queda por lastActivatedAt */ }
}
