package com.example.toto_app.services;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Guarda el último WhatsApp entrante para poder leerlo cuando el usuario diga “sí / léelo”. */
public final class IncomingMessageStore {

    public static final class Msg {
        public final String from;
        public final String body;
        public final long   whenElapsedMs;
        @Nullable public final List<String> parts; // mensajes individuales ya listos para TTS
        Msg(String from, String body, long whenElapsedMs) {
            this(from, body, whenElapsedMs, null);
        }
        Msg(String from, String body, long whenElapsedMs, @Nullable List<String> parts) {
            this.from = from; this.body = body; this.whenElapsedMs = whenElapsedMs; this.parts = parts;
        }
    }

    private static final IncomingMessageStore I = new IncomingMessageStore();
    public static IncomingMessageStore get() { return I; }
    private final Object lock = new Object();

    private String from;
    private String body;
    private long whenMs;
    private boolean awaitingConfirm;
    @Nullable private List<String> parts; // partes crudas para lectura

    // Memoria de mensajes ya leídos por chat (clave: título del chat)
    private final Map<String, LinkedHashSet<String>> spokenByChat = new HashMap<>();
    private static final int SPOKEN_CAP = 100; // cap por chat para evitar crecimiento

    private IncomingMessageStore() {}

    /** Setea/actualiza el último mensaje y marca que estamos esperando confirmación para leer. */
    public void setIncoming(String from, String body) {
        synchronized (lock) {
            this.from = (from == null || from.isEmpty()) ? "alguien" : from;
            this.body = (body == null) ? "" : body;
            this.whenMs = SystemClock.elapsedRealtime();
            this.awaitingConfirm = true;
            this.parts = null;
        }
    }

    /** Variante que además guarda las partes individuales (para marcar como leídas luego). */
    public void setIncoming(String from, String body, @Nullable List<String> parts) {
        synchronized (lock) {
            this.from = (from == null || from.isEmpty()) ? "alguien" : from;
            this.body = (body == null) ? "" : body;
            this.whenMs = SystemClock.elapsedRealtime();
            this.awaitingConfirm = true;
            this.parts = (parts == null || parts.isEmpty()) ? null : new ArrayList<>(parts);
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
            return new Msg(from, body, whenMs, parts == null ? null : new ArrayList<>(parts));
        }
    }

    /** Consume (lee) el mensaje: desmarca confirmación y limpia. */
    @Nullable
    public Msg consume() {
        synchronized (lock) {
            if (body == null) return null;
            Msg m = new Msg(from, body, whenMs, parts == null ? null : new ArrayList<>(parts));
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
        this.parts = null;
    }

    // ====== Dedupe / spoken tracking ======

    /** Normaliza un texto para usar como clave de dedupe. */
    private static String normKey(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[“”\"']", "")
                .replaceAll("\\s+", " ")
                .trim();
        // Limita longitud para evitar claves enormes
        if (t.length() > 280) t = t.substring(0, 280);
        return t;
    }

    private static String chatKey(String from) {
        if (from == null) return "";
        return normKey(from);
    }

    private LinkedHashSet<String> ensureSetLocked(String chat) {
        LinkedHashSet<String> set = spokenByChat.get(chat);
        if (set == null) {
            set = new LinkedHashSet<>();
            spokenByChat.put(chat, set);
        }
        return set;
    }

    /**
     * Filtra las partes que aún no se leyeron (según memoria del chat) y devuelve hasta 'limit' más recientes.
     * NO marca como leídas; eso se hace al confirmar lectura (markSpoken).
     */
    public List<String> filterNewParts(String from, List<String> parts, int limit) {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            if (parts == null || parts.isEmpty()) return out;
            String ck = chatKey(from);
            LinkedHashSet<String> set = ensureSetLocked(ck);
            // preservamos orden original; tomamos sólo las que no estén en "set"
            for (String p : parts) {
                if (p == null) continue;
                String key = normKey(p);
                if (!set.contains(key)) out.add(p);
            }
            // devolver últimas 'limit'
            int n = out.size();
            if (limit > 0 && n > limit) {
                return new ArrayList<>(out.subList(n - limit, n));
            }
            return out;
        }
    }

    /** Marca como leídas estas partes para el chat. */
    public void markSpoken(String from, @Nullable List<String> parts) {
        if (parts == null || parts.isEmpty()) return;
        synchronized (lock) {
            String ck = chatKey(from);
            LinkedHashSet<String> set = ensureSetLocked(ck);
            for (String p : parts) {
                if (p == null) continue;
                String key = normKey(p);
                set.add(key);
            }
            // recortar si excede capacidad
            if (set.size() > SPOKEN_CAP) {
                int toRemove = set.size() - SPOKEN_CAP;
                Iterator<String> it = set.iterator();
                while (toRemove > 0 && it.hasNext()) { it.next(); it.remove(); toRemove--; }
            }
        }
    }
}
