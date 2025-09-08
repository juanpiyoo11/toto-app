package com.example.toto_app.services;

import android.os.SystemClock;

import androidx.annotation.Nullable;

/** Guarda el último WhatsApp entrante para poder leerlo cuando el usuario diga “sí / léelo”. */
public final class IncomingMessageStore {

    public static final class Msg {
        public final String from;
        public final String body;
        public final long   whenElapsedMs;
        Msg(String from, String body, long whenElapsedMs) {
            this.from = from; this.body = body; this.whenElapsedMs = whenElapsedMs;
        }
    }

    private static final IncomingMessageStore I = new IncomingMessageStore();
    public static IncomingMessageStore get() { return I; }
    private final Object lock = new Object();

    private String from;
    private String body;
    private long whenMs;
    private boolean awaitingConfirm;

    private IncomingMessageStore() {}

    /** Setea/actualiza el último mensaje y marca que estamos esperando confirmación para leer. */
    public void setIncoming(String from, String body) {
        synchronized (lock) {
            this.from = (from == null || from.isEmpty()) ? "alguien" : from;
            this.body = (body == null) ? "" : body;
            this.whenMs = SystemClock.elapsedRealtime();
            this.awaitingConfirm = true;
        }
    }

    /** ¿Sigue “fresco” (reciente) y con contenido? */
    public boolean hasFresh(long maxAgeMs) {
        synchronized (lock) {
            if (body == null || body.isEmpty()) return false;
            return (SystemClock.elapsedRealtime() - whenMs) <= maxAgeMs;
        }
    }

    /** ¿Estamos esperando el “sí” del usuario para leer? */
    public boolean isAwaitingConfirm() {
        synchronized (lock) { return awaitingConfirm; }
    }

    /** Devuelve una vista del último mensaje (sin limpiar). */
    @Nullable
    public Msg peek() {
        synchronized (lock) {
            if (body == null) return null;
            return new Msg(from, body, whenMs);
        }
    }

    /** Consume (lee) el mensaje: desmarca confirmación y limpia. */
    @Nullable
    public Msg consume() {
        synchronized (lock) {
            if (body == null) return null;
            Msg m = new Msg(from, body, whenMs);
            clearLocked();
            return m;
        }
    }

    /** Limpia manualmente. */
    public void clear() {
        synchronized (lock) { clearLocked(); }
    }

    private void clearLocked() {
        this.from = null;
        this.body = null;
        this.awaitingConfirm = false;
        this.whenMs = 0L;
    }
}
