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

public final class IncomingMessageStore {

    public static final class Msg {
        public final String from;
        public final String body;
        public final long   whenElapsedMs;
        @Nullable public final List<String> parts;
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
    @Nullable private List<String> parts;

    private final Map<String, LinkedHashSet<String>> spokenByChat = new HashMap<>();
    private static final int SPOKEN_CAP = 100;

    private IncomingMessageStore() {}

    public void setIncoming(String from, String body) {
        synchronized (lock) {
            this.from = (from == null || from.isEmpty()) ? "alguien" : from;
            this.body = (body == null) ? "" : body;
            this.whenMs = SystemClock.elapsedRealtime();
            this.awaitingConfirm = true;
            this.parts = null;
        }
    }

    public void setIncoming(String from, String body, @Nullable List<String> parts) {
        synchronized (lock) {
            this.from = (from == null || from.isEmpty()) ? "alguien" : from;
            this.body = (body == null) ? "" : body;
            this.whenMs = SystemClock.elapsedRealtime();
            this.awaitingConfirm = true;
            this.parts = (parts == null || parts.isEmpty()) ? null : new ArrayList<>(parts);
        }
    }

    public boolean hasFresh(long maxAgeMs) {
        synchronized (lock) {
            if (body == null || body.isEmpty()) return false;
            return (SystemClock.elapsedRealtime() - whenMs) <= maxAgeMs;
        }
    }

    public boolean isAwaitingConfirm() {
        synchronized (lock) { return awaitingConfirm; }
    }

    @Nullable
    public Msg peek() {
        synchronized (lock) {
            if (body == null) return null;
            return new Msg(from, body, whenMs, parts == null ? null : new ArrayList<>(parts));
        }
    }

    @Nullable
    public Msg consume() {
        synchronized (lock) {
            if (body == null) return null;
            Msg m = new Msg(from, body, whenMs, parts == null ? null : new ArrayList<>(parts));
            clearLocked();
            return m;
        }
    }

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

    private static String normKey(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[“”\"']", "")
                .replaceAll("\\s+", " ")
                .trim();
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


    public List<String> filterNewParts(String from, List<String> parts, int limit) {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            if (parts == null || parts.isEmpty()) return out;
            String ck = chatKey(from);
            LinkedHashSet<String> set = ensureSetLocked(ck);
            for (String p : parts) {
                if (p == null) continue;
                String key = normKey(p);
                if (!set.contains(key)) out.add(p);
            }
            int n = out.size();
            if (limit > 0 && n > limit) {
                return new ArrayList<>(out.subList(n - limit, n));
            }
            return out;
        }
    }

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
            if (set.size() > SPOKEN_CAP) {
                int toRemove = set.size() - SPOKEN_CAP;
                Iterator<String> it = set.iterator();
                while (toRemove > 0 && it.hasNext()) { it.next(); it.remove(); toRemove--; }
            }
        }
    }
}
