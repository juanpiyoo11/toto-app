package com.example.toto_app.services;

import android.util.Log;

import com.example.toto_app.AppContext;
import com.example.toto_app.falls.FallLogic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Almacena en memoria los mensajes de emergencia que no pudieron ser enviados.
 * Implementación simple: en memoria, thread-safe; envía al backend cuando se pueda.
 */
public final class PendingEmergencyStore {
    private static final String TAG = "PendingEmergencyStore";
    private static final PendingEmergencyStore I = new PendingEmergencyStore();

    public static PendingEmergencyStore get() { return I; }

    public static final class Item {
        public final String numberE164;
        public final String userName;
        public final long whenMs;
        Item(String numberE164, String userName, long whenMs) { this.numberE164 = numberE164; this.userName = userName; this.whenMs = whenMs; }
    }

    private final Object lock = new Object();
    private final List<Item> list = new ArrayList<>();

    private PendingEmergencyStore() {}

    public void add(String numberE164, String userName) {
        synchronized (lock) {
            list.add(new Item(numberE164, userName, System.currentTimeMillis()));
            Log.i(TAG, "Queued emergency for " + numberE164);
            // notificar al usuario inmediatamente (notificación + TTS)
            try {
                NotificationService.simpleActionNotification(
                        AppContext.get(),
                        "Toto: Sin conexión",
                        "No hay conexión al servidor. No te preocupes, en cuanto vuelva la conexión enviaré el mensaje de emergencia.",
                        null);
                // Start WakeWordService ACTION_SAY with enqueue flag so it will speak now if idle or enqueue otherwise.
                try {
                    Log.d(TAG, "PendingEmergencyStore.add -> starting WakeWordService ACTION_SAY (enqueue)");
                    android.content.Intent i = new android.content.Intent(AppContext.get(), com.example.toto_app.services.WakeWordService.class)
                            .setAction(com.example.toto_app.services.WakeWordService.ACTION_SAY)
                            .putExtra("text", "No hay conexión al servidor. No te preocupes, en cuanto vuelva la conexión enviaré el mensaje de emergencia.")
                            .putExtra(com.example.toto_app.services.WakeWordService.EXTRA_ENQUEUE_IF_BUSY, true);
                    androidx.core.content.ContextCompat.startForegroundService(AppContext.get(), i);
                } catch (Exception ignore) {
                    // fallback: broadcast for older behavior
                    try {
                        Log.d(TAG, "PendingEmergencyStore.add -> startForegroundService failed, broadcasting ACTION_PENDING_SENT");
                        android.content.Intent b = new android.content.Intent("com.example.toto_app.ACTION_PENDING_SENT");
                        b.putExtra("text", "No hay conexión al servidor. No te preocupes, en cuanto vuelva la conexión enviaré el mensaje de emergencia.");
                        AppContext.get().sendBroadcast(b);
                    } catch (Exception ignore2) {}
                }
            } catch (Exception ignore) {}
        }
    }

    public int size() { synchronized (lock) { return list.size(); } }

    /**
     * Intenta enviar todos los pendientes (de forma síncrona). Se llama cuando el backend
     * vuelve vivo (BackendHealthManager lo dispara).
     */
    public void flushPendingNow() {
        synchronized (lock) {
            Iterator<Item> it = list.iterator();
            while (it.hasNext()) {
                Item itx = it.next();
                try {
                    boolean ok = FallLogic.sendEmergencyMessageTo(itx.numberE164, itx.userName);
                    if (ok) {
                        Log.i(TAG, "Sent queued emergency to " + itx.numberE164);
                        it.remove();
                        // notificar al usuario que se envió (local notification + TTS)
                        try {
                            NotificationService.simpleActionNotification(
                                    AppContext.get(),
                                    "Toto: Mensaje de emergencia enviado",
                                    "Se envió el mensaje de emergencia a " + itx.numberE164 + " al recuperar la conexión.",
                                    null);
                            // Ask WakeWordService to speak (enqueue if busy) the confirmation message.
                            try {
                                Log.d(TAG, "PendingEmergencyStore.flushPendingNow -> starting WakeWordService ACTION_SAY (enqueue) for " + itx.numberE164);
                                android.content.Intent say = new android.content.Intent(AppContext.get(), com.example.toto_app.services.WakeWordService.class)
                                        .setAction(com.example.toto_app.services.WakeWordService.ACTION_SAY)
                                        .putExtra("text", "Recuperé la conexión. Envié el mensaje de emergencia.")
                                        .putExtra(com.example.toto_app.services.WakeWordService.EXTRA_ENQUEUE_IF_BUSY, true);
                                androidx.core.content.ContextCompat.startForegroundService(AppContext.get(), say);
                            } catch (Exception ignore) {
                                // fallback to broadcast
                                try {
                                    Log.d(TAG, "PendingEmergencyStore.flushPendingNow -> startForegroundService failed, broadcasting ACTION_PENDING_SENT");
                                    android.content.Intent b = new android.content.Intent("com.example.toto_app.ACTION_PENDING_SENT");
                                    b.putExtra("text", "Recuperé la conexión. Envié el mensaje de emergencia.");
                                    AppContext.get().sendBroadcast(b);
                                } catch (Exception ignore2) {}
                            }
                        } catch (Exception ignore) { }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error sending queued emergency", e);
                }
            }
        }
    }
}
