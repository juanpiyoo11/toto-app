package com.example.toto_app.services;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class WhatsAppListener extends NotificationListenerService {

    private static final String WA = "com.whatsapp";
    private static final String WA_BUSINESS = "com.whatsapp.w4b";

    // NEW: dedupe simple por key/tiempo (evita avisos duplicados en milisegundos)
    private static volatile String lastKey = null;
    private static volatile long   lastWhen = 0L;
    private static final long DEDUPE_MS = 1200;

    // Global announce cooldown to avoid spam when many messages arrive
    private static volatile long lastAnnounceAt = 0L;
    private static final long ANNOUNCE_COOLDOWN_MS = 8000L; // 8s

    private static final String TAG = "WhatsAppListener";

    private static boolean isSummaryLine(String s) {
        if (s == null) return false;
        String low = s.toLowerCase().trim();
        if (low.isEmpty()) return false;
        // patterns like "2 new messages", "2 mensajes" or localized variations
        if (low.matches(".*\\b\\d+\\s+(mensajes|messages|new messages)\\b.*")) return true;
        // patterns like "5 messages from 2 chats" / "5 mensajes de 2 chats"
        if (low.matches(".*\\b\\d+\\s+(mensajes|messages)\\s+(de|from)\\s+\\d+\\s+(chats|conversaciones)\\b.*")) return true;
        // some devices show transient summaries like "Checking for new messages"
        if (low.contains("checking for new messages") || low.contains("buscando mensajes nuevos")) return true;
        // very short generic lines that include 'mensajes' are almost always summaries
        if ((low.contains("new messages") || low.contains("nuevos mensajes") || low.contains("mensajes")) && low.length() < 40) return true;
        return false;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').trim();
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(); }

    private static String maybeStripPrefix(String chatTitle, String line) {
        if (line == null) return null;
        String s = line.trim();
        int idx = s.indexOf(":");
        if (idx > 0) {
            String prefix = s.substring(0, idx).trim();
            String rest   = s.substring(idx + 1).trim();
            // si el prefijo coincide con el título del chat (1:1), lo quitamos
            if (!prefix.isEmpty() && !rest.isEmpty()) {
                String ct = chatTitle == null ? "" : chatTitle.trim();
                if (!ct.isEmpty() && prefix.equalsIgnoreCase(ct)) {
                    return rest;
                }
            }
        }
        return s;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!WA.equals(pkg) && !WA_BUSINESS.equals(pkg)) return;

        // NEW: dedupe
        String key = sbn.getKey();
        long now = android.os.SystemClock.elapsedRealtime();
        if (key != null && key.equals(lastKey) && (now - lastWhen) < DEDUPE_MS) return;
        lastKey = key; lastWhen = now;

        Notification n = sbn.getNotification();
        if (n == null) return;

        Bundle ex = n.extras;
        String from = ex.getString(Notification.EXTRA_TITLE);
        CharSequence bodyCs = ex.getCharSequence(Notification.EXTRA_TEXT);
        String body = bodyCs != null ? bodyCs.toString() : null;

        // If EXTRA_TEXT itself looks like a summary, prefer inspecting lines or big text
        if (isSummaryLine(body)) {
            Log.d(TAG, "EXTRA_TEXT looks like a summary: '" + body + "' -> try to get lines/bigText");
            body = null;
        }

        // Recolectar partes a partir de lines/bigText/body
        List<String> parts = new ArrayList<>();

        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            for (CharSequence cs : lines) {
                if (cs == null) continue;
                String s = normalize(cs.toString());
                if (s.isEmpty()) continue;
                if (isSummaryLine(s)) continue;
                s = maybeStripPrefix(from, s);
                if (!s.isEmpty()) parts.add(s);
            }
        }

        if (parts.isEmpty()) {
            CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (big != null) {
                String s = normalize(big.toString());
                if (!isSummaryLine(s) && !s.isEmpty()) {
                    // intentar split simple por "\n" o ". " si hay múltiples
                    String[] cand = s.split("\\n");
                    if (cand.length <= 1) cand = s.split("\\. ");
                    for (String c : cand) { if (!c.trim().isEmpty()) parts.add(c.trim()); }
                }
            }
        }

        if (parts.isEmpty() && !TextUtils.isEmpty(body)) {
            String s = normalize(body);
            if (!isSummaryLine(s) && !s.isEmpty()) parts.add(maybeStripPrefix(from, s));
        }

        // Dedupe simple consecutivo
        if (parts.size() >= 2) {
            List<String> ded = new ArrayList<>();
            String last = null;
            for (String p : parts) {
                if (p == null) continue;
                if (last != null && lower(last).equals(lower(p))) continue;
                ded.add(p); last = p;
            }
            parts = ded;
        }

        if (TextUtils.isEmpty(from)) from = "alguien";

        if (parts.isEmpty()) {
            // no tenemos contenido útil; setear vacío y salir
            IncomingMessageStore.get().setIncoming(from, "");
            Log.d(TAG, "notification had only summary/empty lines -> stored empty incoming for " + from);
            return;
        }

        // construir body de compatibilidad (últimas 2 partes)
        String compatBody;
        if (parts.size() == 1) compatBody = parts.get(0);
        else compatBody = parts.get(parts.size() - 2) + ". " + parts.get(parts.size() - 1);

        IncomingMessageStore.get().setIncoming(from, compatBody, parts);
        Log.i(TAG, "enqueue from='" + from + "' body='" + compatBody + "' parts=" + parts.size());

        // Si hay conversación en curso o TTS hablando, no interrumpimos: delegamos en WakeWordService (enqueue)
        boolean convo = InstructionService.isConversationActive();

        // Apply cooldown only if NO conversation is active (to avoid losing prompts that should be queued)
        if (!convo) {
            if ((now - lastAnnounceAt) < ANNOUNCE_COOLDOWN_MS) {
                Log.d(TAG, "skipping immediate announce due to cooldown");
                return;
            }
            lastAnnounceAt = now;
        }

        Intent i = new Intent(this, WakeWordService.class);
        i.setAction(WakeWordService.ACTION_SAY);
        i.putExtra("text", "Te llegó un mensaje de " + from + ". ¿Querés que te lo lea?");
        i.putExtra(WakeWordService.EXTRA_ENQUEUE_IF_BUSY, true);
        i.putExtra(WakeWordService.EXTRA_AFTER_SAY_START_SERVICE, true);
        i.putExtra(WakeWordService.EXTRA_AFTER_SAY_USER_NAME, from);
        i.putExtra(WakeWordService.EXTRA_AFTER_SAY_CONFIRM_WHATSAPP, true);
        androidx.core.content.ContextCompat.startForegroundService(this, i);
    }
}
