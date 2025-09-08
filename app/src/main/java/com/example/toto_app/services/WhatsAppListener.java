package com.example.toto_app.services;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

public class WhatsAppListener extends NotificationListenerService {

    private static final String WA = "com.whatsapp";
    private static final String WA_BUSINESS = "com.whatsapp.w4b";

    // NEW: dedupe simple por key/tiempo (evita avisos duplicados en milisegundos)
    private static volatile String lastKey = null;
    private static volatile long   lastWhen = 0L;
    private static final long DEDUPE_MS = 1200;

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

        // Mejor cobertura: bigText y textLines
        if (TextUtils.isEmpty(body)) {
            CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (big != null) body = big.toString();
        }
        if (TextUtils.isEmpty(body)) {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                body = lines[lines.length - 1].toString();
            }
        }
        if (TextUtils.isEmpty(from)) from = "alguien";
        if (TextUtils.isEmpty(body)) body = "mensaje nuevo";

        IncomingMessageStore.get().setIncoming(from, body);

        // Aviso hablado
        Intent i = new Intent(this, WakeWordService.class);
        i.setAction(WakeWordService.ACTION_SAY);
        i.putExtra("text", "Te llegó un mensaje de " + from + ". ¿Querés que te lo lea?");
        androidx.core.content.ContextCompat.startForegroundService(this, i);
    }
}
