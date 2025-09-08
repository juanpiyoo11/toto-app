// InstructionService.java
package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.role.RoleManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.util.Log;

import com.example.toto_app.network.WhatsAppSendRequest;
import com.example.toto_app.network.WhatsAppSendResponse;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.toto_app.actions.DeviceActions;
import com.example.toto_app.audio.InstructionCapture;
import com.example.toto_app.audio.LocalStt;
import com.example.toto_app.network.APIService;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.TranscriptionResponse;
import com.example.toto_app.nlp.InstructionRouter;
import com.example.toto_app.nlp.QuickTimeParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Response;

public class InstructionService extends Service {

    private static final String TAG = "InstructionService";
    private static final String CALL_CHANNEL_ID = "toto_call_confirm";
    private static final int CALL_NOTIFY_ID_BASE = 223400;

    private static final String TEMP_FG_CHANNEL_ID = "toto_temp_fg";
    private static final int TEMP_FG_NOTIFY_ID = 998711;

    private String userName = "Juan";

    // ===== Flujo de caída (fases) =====
    private static final String EXTRA_FALL_MODE = "fall_mode"; // enviado por FallTriggerReceiver o por WakeWordService
    @Nullable private String fallMode = null; // "CHECK" | "AWAIT" | "AWAIT_ACTION"
    // [FALL-RETRY] contador de repregunta (0 o 1). Se codifica en fall_mode como "AWAIT:0" o "AWAIT:1"
    private int fallRetry = 0;
    // ===================================

    // ===== Circuit breaker simple para backend =====
    private static final class NetBreaker {
        private static int fails = 0;
        private static long backoffUntilMs = 0L;

        private static final long[] SCHEDULE = new long[] {
                15_000L,   // 1ª falla → 15s
                60_000L,   // 2ª → 60s
                300_000L,  // 3ª → 5min
                1_800_000L // 4ª+ → 30min
        };

        static synchronized boolean allow(Context ctx) {
            long now = SystemClock.uptimeMillis();
            if (now < backoffUntilMs) return false;
            return hasValidatedInternet(ctx);
        }

        static synchronized void success() {
            fails = 0;
            backoffUntilMs = 0L;
        }

        static synchronized void fail() {
            fails++;
            int idx = Math.min(fails - 1, SCHEDULE.length - 1);
            backoffUntilMs = SystemClock.uptimeMillis() + SCHEDULE[idx];
        }
    }

    private static boolean hasValidatedInternet(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return false;
        }
    }
    // ===================================

    // ===== Contacto de emergencia (por ahora hardcodeado) =====
    private static final String EMERGENCY_NAME   = "Tamara";
    private static final String EMERGENCY_NUMBER = "+5491158550932";
    private static String buildEmergencyText(String userName) {
        String u = (userName == null || userName.isBlank()) ? "la persona" : userName;
        return "⚠️ Alerta: " + u + " puede haberse caído o pidió ayuda. "
                + "Este aviso fue enviado automáticamente por Toto para que puedan comunicarse";
    }
    private boolean sendEmergencyMessageToEmergencyContact() {
        try {
            String to = EMERGENCY_NUMBER.replaceAll("[^0-9+]", "");
            String msg = buildEmergencyText(userName);
            WhatsAppSendRequest wreq = new WhatsAppSendRequest(to, msg, Boolean.FALSE);
            retrofit2.Response<WhatsAppSendResponse> wresp = RetrofitClient.api().waSend(wreq).execute();
            WhatsAppSendResponse wbody = wresp.body();
            return wresp.isSuccessful()
                    && wbody != null
                    && ("ok".equalsIgnoreCase(wbody.status)
                    || "ok_template".equalsIgnoreCase(wbody.status)
                    || (wbody.id != null && !wbody.id.trim().isEmpty()));
        } catch (Exception ex) {
            android.util.Log.e(TAG, "Error enviando WhatsApp a emergencia", ex);
            return false;
        }
    }
    // ===========================================================

    @Override
    public void onCreate() { super.onCreate(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("user_name")) {
                String incoming = intent.getStringExtra("user_name");
                if (incoming != null && !incoming.trim().isEmpty()) userName = incoming.trim();
            }
            if (intent.hasExtra(EXTRA_FALL_MODE)) {
                String fmRaw = intent.getStringExtra(EXTRA_FALL_MODE);
                if (fmRaw != null && !fmRaw.trim().isEmpty()) {
                    String upper = fmRaw.trim().toUpperCase(Locale.ROOT);
                    String[] parts = upper.split(":", 2);
                    fallMode = parts[0];
                    if (parts.length > 1) {
                        try { fallRetry = Integer.parseInt(parts[1]); } catch (Exception ignore) {}
                    }
                }
            }
        }
        Executors.newSingleThreadExecutor().execute(this::doWork);
        return START_NOT_STICKY;
    }

    private void doWork() {
        // ── Fase CHECK: preguntar y encadenar escucha (sin wakeword) ──
        if ("CHECK".equals(fallMode)) {
            String prompt = "Escuché un golpe. ¿Estás bien?";
            sayThenListenHere(prompt, "AWAIT:0");
            stopSelf();
            return;
        }

        // Config de captura
        File cacheDir = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
        File wav = new File(cacheDir, "toto_instruction_" + SystemClock.elapsedRealtime() + ".wav");

        InstructionCapture.Config cfg = new InstructionCapture.Config();
        cfg.sampleRate = 16000;
        cfg.maxDurationMs = 15000;
        cfg.trailingSilenceMs = 1800;
        cfg.silenceDbfs = -45.0;
        cfg.frameMs = 30;

        if ("AWAIT".equals(fallMode) || "AWAIT_ACTION".equals(fallMode)) {
            cfg.maxDurationMs     = 12000;
            cfg.trailingSilenceMs = 3500;
            cfg.silenceDbfs       = -50.0;
        }

        Log.d(TAG, "Grabando a WAV: " + wav.getAbsolutePath());
        InstructionCapture.captureToWav(wav, cfg, new InstructionCapture.Listener(){});

        // --- VAD ADAPTATIVO previo a STT ---
        int minVoicedMs =
                ("AWAIT".equals(fallMode) || "AWAIT_ACTION".equals(fallMode) || "CHECK".equals(fallMode))
                        ? 220
                        : 320;

        if (!hasEnoughVoice(wav, cfg.silenceDbfs, minVoicedMs)) {
            Log.d(TAG, "VAD: silencio o voz insuficiente");
            if ("AWAIT".equals(fallMode)) {
                if (fallRetry <= 0) {
                    sayThenListenHere("No te escuché. ¿Estás bien?", "AWAIT:1");
                } else {
                    boolean ok = sendEmergencyMessageToEmergencyContact();
                    if (ok) sayViaWakeService("No te escuché. Ya avisé a " + EMERGENCY_NAME + ".", 10000);
                    else    sayViaWakeService("No te escuché y no pude avisar a " + EMERGENCY_NAME + ".", 12000);
                }
                try { wav.delete(); } catch (Exception ignore) {}
                stopSelf();
                return;
            } else {
                sayViaWakeService("No te escuché bien.", 5000);
                try { wav.delete(); } catch (Exception ignore) {}
                stopSelf();
                return;
            }
        }
        // --- fin VAD ---

        String transcript = "";

        try {
            final boolean inFallPhase = "AWAIT".equals(fallMode) || "AWAIT_ACTION".equals(fallMode);

            if (inFallPhase) {
                try {
                    LocalStt.init(this);
                    String gram = LocalStt.transcribeFile(wav, LocalStt.AWAIT_GRAMMAR);
                    if (!gram.isEmpty()) {
                        transcript = gram;
                        Log.d(TAG, "INSTRUCCIÓN (local STT, grammar): " + transcript);
                    } else {
                        String free = LocalStt.transcribeFile(wav, null);
                        transcript = free == null ? "" : free.trim();
                        Log.d(TAG, "INSTRUCCIÓN (local STT, libre): " + transcript);
                    }
                } catch (Exception le) { // IOException si querés ser estricto
                    Log.e(TAG, "STT local falló en modo caída", le);
                    transcript = "";
                }
            } else {
                // === FLUJO NORMAL: LOCAL-FIRST; remoto solo si queda vacío y está permitido ===
                try {
                    LocalStt.init(this);
                    String free = LocalStt.transcribeFile(wav, null);
                    transcript = free == null ? "" : free.trim();
                    Log.d(TAG, "INSTRUCCIÓN (local STT): " + transcript);
                } catch (Exception le) {
                    Log.e(TAG, "STT local falló", le);
                    transcript = "";
                }

                boolean needRemote = transcript.isEmpty() || transcript.length() < 3;
                if (needRemote && NetBreaker.allow(this)) {
                    try {
                        APIService apiFast = RetrofitClient.apiFast();
                        RequestBody fileBody = RequestBody.create(wav, MediaType.parse("audio/wav"));
                        MultipartBody.Part audioPart = MultipartBody.Part.createFormData("audio", wav.getName(), fileBody);
                        Response<TranscriptionResponse> resp = apiFast.transcribe(audioPart, null, null).execute();
                        if (resp.isSuccessful() && resp.body() != null) {
                            String t = resp.body().text != null ? resp.body().text.trim() : "";
                            if (!t.isEmpty()) {
                                transcript = t;
                                NetBreaker.success();
                                Log.d(TAG, "INSTRUCCIÓN (backend STT): " + transcript);
                            } else {
                                NetBreaker.fail();
                            }
                        } else {
                            NetBreaker.fail();
                            Log.e(TAG, "Transcribe error HTTP: " + (resp != null ? resp.code() : -1));
                        }
                    } catch (Exception e) {
                        NetBreaker.fail();
                        Log.e(TAG, "Error llamando al backend STT", e);
                    }
                }
            }
        } finally {
            try { wav.delete(); } catch (Exception ignored) {}
        }

        // ── Fase AWAIT: decidir acción ──
        if ("AWAIT".equals(fallMode)) {
            String norm = normEs(transcript);

            if (norm.isEmpty()) {
                if (fallRetry <= 0) {
                    sayThenListenHere("No te escuché. ¿Estás bien?", "AWAIT:1");
                } else {
                    boolean ok = sendEmergencyMessageToEmergencyContact();
                    if (ok) sayViaWakeService("No te escuché. Ya avisé a " + EMERGENCY_NAME + ".", 10000);
                    else    sayViaWakeService("No te escuché y no pude avisar a " + EMERGENCY_NAME + ".", 12000);
                }
                stopSelf();
                return;
            }

            FallReply fr = assessFallReply(norm);
            switch (fr) {
                case HELP: {
                    boolean ok = sendEmergencyMessageToEmergencyContact();
                    if (ok) sayViaWakeService("Ya avisé a " + EMERGENCY_NAME + ".", 8000);
                    else    sayViaWakeService("Quise avisar a " + EMERGENCY_NAME + " pero no pude enviar el mensaje.", 10000);
                    stopSelf();
                    return;
                }
                case OK: {
                    sayViaWakeService("Me alegro. Si necesitás ayuda, decime.", 5000);
                    stopSelf();
                    return;
                }
                case UNKNOWN:
                default: {
                    sayThenListenHere("No me quedó claro. ¿Estás bien?", "AWAIT:" + fallRetry);
                    stopSelf();
                    return;
                }
            }
        }

        // ── Flujo normal ──
        if (transcript.isEmpty()) {
            sayViaWakeService("No te escuché bien.", 6000);
            stopSelf();
            return;
        }

        // === FALL TRIGGER GLOBAL ===
        String normAll = normEs(transcript);
        if (saysHelp(normAll) || mentionsFall(normAll)) {
            Log.d(TAG, "Fall trigger (global): ayuda/caída detectada → preguntar primero");
            sayThenListenHere("¿Estás bien?", "AWAIT:0");
            stopSelf();
            return;
        }
        // === FIN FALL TRIGGER GLOBAL ===

        // === Lectura de WhatsApp entrante ===
        try {
            String norm = java.text.Normalizer.normalize(transcript, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[¿?¡!.,;:()\\[\\]\"]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            boolean saysAffirm =
                    norm.matches("^(si|dale|claro|ok|de una|bueno|obvio|por favor|si por favor|si dale)(\\s.*)?$");

            boolean saysRead =
                    norm.contains("leelo") || norm.contains("leela") ||
                            norm.contains("leerlo") || norm.contains("leeme") ||
                            norm.contains("leermelo") || norm.contains("leermela") ||
                            norm.contains("lee el mensaje") || norm.contains("leelo por favor") ||
                            norm.contains("leela por favor");

            final long FRESH_MS = 3 * 60_000L;
            boolean fresh = IncomingMessageStore.get().hasFresh(FRESH_MS);

            if (fresh && ((IncomingMessageStore.get().isAwaitingConfirm() && saysAffirm) || saysRead)) {
                IncomingMessageStore.Msg m = IncomingMessageStore.get().consume();
                if (m != null) {
                    String tts = (m.from == null ? "alguien" : m.from) + " dice: " + (m.body == null ? "…" : m.body);
                    sayViaWakeService(tts, 8000);
                    stopSelf();
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // ==== Alarmas locales ====
        boolean wantsAlarm = looksLikeAlarmRequest(transcript);
        Log.d(TAG, "Alarm gate=" + wantsAlarm);
        if (wantsAlarm) {
            QuickTimeParser.Relative rel = QuickTimeParser.parseRelativeEsAr(transcript);
            if (rel != null && rel.minutesTotal > 0) {
                Calendar target = Calendar.getInstance();
                target.add(Calendar.MINUTE, rel.minutesTotal);
                int hh = target.get(Calendar.HOUR_OF_DAY);
                int mm = target.get(Calendar.MINUTE);
                DeviceActions.AlarmResult res = DeviceActions.setAlarm(this, hh, mm, "Toto");
                handleAlarmResult(res, DeviceActions.hhmm(hh, mm));
                stopSelf();
                return;
            }

            QuickTimeParser.Absolute abs = QuickTimeParser.parseAbsoluteEsAr(transcript);
            if (abs != null) {
                Calendar now = Calendar.getInstance();
                Calendar target = Calendar.getInstance();
                target.set(Calendar.HOUR_OF_DAY, abs.hour24);
                target.set(Calendar.MINUTE, abs.minute);
                target.set(Calendar.SECOND, 0);
                target.set(Calendar.MILLISECOND, 0);
                if (target.before(now)) target.add(Calendar.DATE, 1);
                int hh = target.get(Calendar.HOUR_OF_DAY);
                int mm = target.get(Calendar.MINUTE);
                DeviceActions.AlarmResult res = DeviceActions.setAlarm(this, hh, mm, "Toto");
                handleAlarmResult(res, DeviceActions.hhmm(hh, mm));
                stopSelf();
                return;
            }
        }
        // ==== Fin alarmas ====

        // 3) NLU remoto con fallback local (gobernado por breaker)
        String intentName = null;
        com.example.toto_app.network.NluRouteResponse nres = null;

        if (NetBreaker.allow(this)) {
            try {
                com.example.toto_app.network.NluRouteRequest nreq = new com.example.toto_app.network.NluRouteRequest();
                nreq.text = transcript;
                nreq.locale = "es-AR";
                nreq.context = null;
                nreq.hints = null;
                retrofit2.Response<com.example.toto_app.network.NluRouteResponse> rNlu =
                        RetrofitClient.apiFast().nluRoute(nreq).execute();
                if (rNlu.isSuccessful()) {
                    nres = rNlu.body();
                    NetBreaker.success();
                } else {
                    NetBreaker.fail();
                }
            } catch (Exception e) {
                NetBreaker.fail();
                Log.e(TAG, "Error llamando /api/nlu/route", e);
            }
        }

        if (nres != null) {
            Log.d(TAG, "NLU/route → intent=" + safe(nres.intent)
                    + " conf=" + nres.confidence
                    + " needsConf=" + nres.needs_confirmation
                    + " slots=" + slotsToString(nres.slots)
                    + " ack=" + safe(nres.ack_tts));
        } else {
            Log.d(TAG, "NLU/route → nres=null (usando router local)");
        }

        if (nres != null && nres.intent != null && !nres.intent.trim().isEmpty()) {
            intentName = nres.intent.trim().toUpperCase(java.util.Locale.ROOT);
        } else {
            InstructionRouter.Result fb0 = InstructionRouter.route(transcript);
            intentName = mapLegacyActionToIntent(fb0.action);
            if (nres == null) nres = new com.example.toto_app.network.NluRouteResponse();
            if ("SET_ALARM".equals(intentName)) {
                if (nres.slots == null) nres.slots = new com.example.toto_app.network.NluRouteResponse.Slots();
                if (nres.slots.hour == null)   nres.slots.hour   = fb0.hour;
                if (nres.slots.minute == null) nres.slots.minute = fb0.minute;
            }
            if ("CALL".equals(intentName)) {
                if (nres.slots == null) nres.slots = new com.example.toto_app.network.NluRouteResponse.Slots();
                if (nres.slots.contact_query == null) nres.slots.contact_query = fb0.contactName;
            }
        }

        InstructionRouter.Result fb = InstructionRouter.route(transcript);
        String fbIntent = mapLegacyActionToIntent(fb.action);
        if (intentName == null || "ANSWER".equals(intentName) || "UNKNOWN".equals(intentName)) {
            if ("QUERY_TIME".equals(fbIntent) || "QUERY_DATE".equals(fbIntent)
                    || "CALL".equals(fbIntent) || "SET_ALARM".equals(fbIntent)) {
                intentName = fbIntent;
                if (nres == null) nres = new com.example.toto_app.network.NluRouteResponse();
                if (nres.slots == null) nres.slots = new com.example.toto_app.network.NluRouteResponse.Slots();
                if ("SET_ALARM".equals(intentName)) {
                    if (nres.slots.hour == null)   nres.slots.hour   = fb.hour;
                    if (nres.slots.minute == null) nres.slots.minute = fb.minute;
                } else if ("CALL".equals(intentName)) {
                    if (nres.slots.contact_query == null) nres.slots.contact_query = fb.contactName;
                }
            }
        }

        Log.d(TAG, "Intent final → " + (intentName == null ? "null" : intentName)
                + " (fbIntent=" + fbIntent + ")");

        if (intentName == null || intentName.trim().isEmpty()) intentName = "UNKNOWN";

        if (nres != null && nres.ack_tts != null && !nres.ack_tts.trim().isEmpty()) {
            boolean isAnswerish = "ANSWER".equals(intentName) || "UNKNOWN".equals(intentName);
            if (!"QUERY_TIME".equals(intentName) && !"QUERY_DATE".equals(intentName)
                    && !isAnswerish && !"CALL".equals(intentName)
                    && !"SEND_MESSAGE".equals(intentName)) {
                sayViaWakeService(sanitizeForTTS(nres.ack_tts), 6000);
            }
        }

        if (nres != null && nres.needs_confirmation && nres.clarifying_question != null
                && !nres.clarifying_question.trim().isEmpty()) {
            sayViaWakeService(sanitizeForTTS(nres.clarifying_question.trim()), 8000);
            stopSelf();
            return;
        }

        // 4) Ejecutar intención
        switch (intentName) {
            case "QUERY_TIME": {
                Calendar c = Calendar.getInstance();
                sayViaWakeService("Son las " + DeviceActions.hhmm(
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + ".", 6000);
                stopSelf();
                return;
            }
            case "QUERY_DATE": {
                Locale esAR = new Locale("es", "AR");
                Calendar c = Calendar.getInstance();
                SimpleDateFormat fmt = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", esAR);
                String pretty = capitalizeFirst(fmt.format(c.getTime()), esAR);
                sayViaWakeService("Hoy es " + pretty + ".", 6000);
                stopSelf();
                return;
            }
            case "SET_ALARM": {
                Integer hh = (nres != null && nres.slots != null) ? nres.slots.hour : null;
                Integer mm = (nres != null && nres.slots != null) ? nres.slots.minute : null;

                if ((hh == null || mm == null) && nres != null && nres.slots != null
                        && nres.slots.datetime_iso != null && !nres.slots.datetime_iso.isEmpty()) {
                    int[] hm = tryParseIsoToLocalHourMinute(nres.slots.datetime_iso);
                    if (hm != null) { hh = hm[0]; mm = hm[1]; }
                }
                if (hh == null || mm == null) {
                    sayViaWakeService("¿Para qué hora querés la alarma?", 8000);
                    stopSelf();
                    return;
                }
                DeviceActions.AlarmResult res = DeviceActions.setAlarm(this, hh, mm, "Toto");
                handleAlarmResult(res, DeviceActions.hhmm(hh, mm));
                stopSelf();
                return;
            }

            case "CALL": {
                String who = (nres != null && nres.slots != null) ? nres.slots.contact_query : null;
                if (who == null || who.trim().isEmpty()) {
                    sayViaWakeService("¿A quién querés que llame?", 8000);
                    stopSelf();
                    return;
                }

                boolean hasContacts = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.READ_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasContacts) {
                    sayViaWakeService("Necesito permiso de contactos para llamar por nombre. Abrí la app para darlo.", 10000);
                    Intent perm = new Intent(this, com.example.toto_app.MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("request_contacts_perm", true);
                    startActivity(perm);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                DeviceActions.ResolvedContact rc = DeviceActions.resolveContactByNameFuzzy(this, who);
                if ((rc == null || rc.number == null || rc.number.isEmpty())
                        && who.startsWith("a") && who.length() >= 3) {
                    rc = DeviceActions.resolveContactByNameFuzzy(this, who.substring(1));
                }
                if ((rc == null || rc.number == null || rc.number.isEmpty()) && looksLikePhoneNumber(who)) {
                    String dial = normalizeDialable(who);
                    rc = new DeviceActions.ResolvedContact(who, dial, 1.0);
                }

                if (rc == null || rc.number == null || rc.number.isEmpty()) {
                    showCallNotification(who, null);
                    sayViaWakeService("No encontré a " + who + " en tus contactos. Te dejé una notificación para marcar.", 12000);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                if (isDeviceLocked()) {
                    showCallNotification(rc.name, rc.number);
                    sayViaWakeService("Desbloqueá y tocá la notificación para llamar a " + rc.name + ". O pedímelo de nuevo con el celu desbloqueado.", 12000);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                boolean hasCall = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CALL_PHONE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasCall && !isDefaultDialer()) {
                    sayViaWakeService("Necesito permiso de llamadas para marcar directamente. Abrí la app para darlo.", 10000);
                    Intent perm = new Intent(this, com.example.toto_app.MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("request_call_phone_perm", true);
                    startActivity(perm);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                boolean ok = placeDirectCall(rc.name, rc.number);
                if (ok) {
                    sayViaWakeService("Llamando a " + rc.name + ".", 4000);
                } else {
                    showCallNotification(rc.name, rc.number);
                    sayViaWakeService("No pude iniciar la llamada directa. Te dejé una notificación para marcar.", 12000);
                }

                postWatchdog(8000);
                stopSelf();
                return;
            }

            case "SEND_MESSAGE": {
                String who = (nres != null && nres.slots != null) ? nres.slots.contact_query : null;
                String msg = (nres != null && nres.slots != null) ? nres.slots.message_text : null;

                if ((who == null || who.isBlank()) || (msg == null || msg.isBlank())) {
                    FallbackMessage fm = fallbackExtractMessage(transcript);
                    if (who == null || who.isBlank()) who = (fm != null ? fm.who : null);
                    if (msg == null || msg.isBlank()) msg = (fm != null ? fm.text : null);
                }

                if (who == null || who.trim().isEmpty()) {
                    sayViaWakeService("¿A quién querés mandarle el mensaje?", 9000);
                    stopSelf();
                    return;
                }

                boolean hasContacts = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.READ_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasContacts) {
                    sayViaWakeService("Necesito permiso de contactos para mandar por nombre. Abrí la app para darlo.", 10000);
                    Intent perm = new Intent(this, com.example.toto_app.MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("request_contacts_perm", true);
                    startActivity(perm);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                DeviceActions.ResolvedContact rc = DeviceActions.resolveContactByNameFuzzy(this, who);
                if (rc == null || rc.number == null || rc.number.isEmpty()) {
                    sayViaWakeService("No encontré a " + who + " en tus contactos.", 9000);
                    stopSelf();
                    return;
                }

                if (msg == null || msg.trim().isEmpty()) {
                    sayViaWakeService("¿Qué querés que le diga a " + rc.name + "?", 9000);
                    stopSelf();
                    return;
                }

                String to = rc.number.replaceAll("[^0-9+]", "");
                try {
                    WhatsAppSendRequest wreq = new WhatsAppSendRequest(to, msg.trim(), Boolean.FALSE);
                    retrofit2.Response<WhatsAppSendResponse> wresp =
                            RetrofitClient.api().waSend(wreq).execute();

                    WhatsAppSendResponse wbody = wresp.body();
                    boolean ok =
                            wresp.isSuccessful()
                                    && wbody != null
                                    && (
                                    "ok".equalsIgnoreCase(wbody.status)
                                            || "ok_template".equalsIgnoreCase(wbody.status)
                                            || (wbody.id != null && !wbody.id.trim().isEmpty())
                            );

                    if (ok) {
                        sayViaWakeService("Listo, le mandé el mensaje a " + rc.name + ".", 8000);
                    } else {
                        String err = null;
                        try { err = (wresp.errorBody() != null) ? wresp.errorBody().string() : null; } catch (Exception ignored) {}
                        Log.e(TAG, "WA send failed: HTTP=" + (wresp != null ? wresp.code() : -1)
                                + " status=" + (wbody != null ? wbody.status : "null")
                                + " id=" + (wbody != null ? wbody.id : "null")
                                + " err=" + err);

                        if (err != null && (err.contains("recipient_not_allowed") || err.contains("131030"))) {
                            sayViaWakeService("No pude enviar por WhatsApp porque ese número no está autorizado aún. Agregalo como destinatario de prueba en WhatsApp Manager.", 12000);
                        } else {
                            sayViaWakeService("No pude mandar el mensaje ahora.", 8000);
                        }
                    }
                } catch (Exception ex) {
                    android.util.Log.e(TAG, "Error /api/whatsapp/send", ex);
                    sayViaWakeService("Tuve un problema mandando el mensaje.", 8000);
                }

                stopSelf();
                return;
            }

            case "CANCEL": {
                sayViaWakeService("Listo.", 6000);
                stopSelf();
                return;
            }
            case "ANSWER":
            case "UNKNOWN":
            default: {
                // Pregunta abierta: sólo si el backend está OK.
                if (NetBreaker.allow(this)) {
                    try {
                        com.example.toto_app.network.AskRequest rq = new com.example.toto_app.network.AskRequest();
                        rq.prompt = transcript;
                        retrofit2.Response<com.example.toto_app.network.AskResponse> r2 =
                                RetrofitClient.apiFast().ask(rq).execute();
                        if (r2.isSuccessful() && r2.body() != null && r2.body().reply != null) {
                            NetBreaker.success();
                            String reply = r2.body().reply.trim();
                            sayViaWakeService(sanitizeForTTS(reply), 12000);
                        } else {
                            NetBreaker.fail();
                            sayViaWakeService("Ahora no tengo conexión para responder eso.", 6000);
                        }
                    } catch (Exception ex) {
                        NetBreaker.fail();
                        Log.e(TAG, "Error /api/ask", ex);
                        sayViaWakeService("Ahora no tengo conexión para responder eso.", 6000);
                    }
                } else {
                    sayViaWakeService("Ahora no tengo conexión para responder eso.", 6000);
                }
                stopSelf();
                return;
            }
        }
    }

    // ==== Helpers de flujo de caída ====

    private void sayThenListenHere(String text, @Nullable String nextFallMode) {
        Intent say = new Intent(this, WakeWordService.class)
                .setAction(WakeWordService.ACTION_SAY)
                .putExtra("text", sanitizeForTTS(text))
                .putExtra(WakeWordService.EXTRA_AFTER_SAY_START_SERVICE, true)
                .putExtra(WakeWordService.EXTRA_AFTER_SAY_USER_NAME, userName);
        if (nextFallMode != null) {
            say.putExtra(WakeWordService.EXTRA_AFTER_SAY_FALL_MODE, nextFallMode);
        }
        androidx.core.content.ContextCompat.startForegroundService(this, say);
    }

    private static String normEs(String raw) {
        if (raw == null) return "";
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT);

        s = s.replaceAll("([.,])", " $1 ");
        s = s.replaceAll("[¿?¡!;:()\\[\\]\"]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return " " + s + " ";
    }

    // Clasificación de la respuesta en AWAIT
    private enum FallReply { OK, HELP, UNKNOWN }

    private static boolean hasStandaloneNo(String norm) {
        return norm.matches(".*\\bno\\b.*")
                && !norm.contains(" no fue nada ")
                && !norm.contains(" no te preocupes ")
                && !norm.contains(" no hay problema ")
                && !norm.contains(" no gracias ")
                && !saysOk(norm);
    }

    private static FallReply assessFallReply(String norm) {
        if (saysHelp(norm)) return FallReply.HELP;
        if (saysOk(norm))   return FallReply.OK;
        if (mentionsFall(norm)) return FallReply.HELP;
        if (hasStandaloneNo(norm)) return FallReply.HELP;
        return FallReply.UNKNOWN;
    }

    private static boolean saysOk(String norm) {
        if (norm.contains(" no me puedo mover ")
                || norm.contains(" no puedo levantarme ") || norm.contains(" no puedo pararme ")
                || norm.contains(" estoy mal ")
                || norm.contains(" me duele ")
                || norm.contains(" me lastime ") || norm.contains(" me lastimé ")) {
            return false;
        }
        if (norm.matches(".*\\bno\\s+estoy\\s+bien\\b.*")) return false;
        if (norm.matches(".*\\bno\\s+esta\\s+bien\\b.*")) return false;

        return norm.contains(" estoy bien ")
                || norm.contains(" esta bien ")
                || norm.contains(" esta todo bien ")
                || norm.contains(" todo bien ")
                || norm.contains(" todo ok ")
                || norm.contains(" estoy ok ")
                || norm.contains(" tranquilo ") || norm.contains(" tranquila ")
                || norm.contains(" ya estoy bien ")
                || norm.contains(" no fue nada ")
                || norm.contains(" no te preocupes ")
                || norm.contains(" no hay problema ")
                || norm.contains(" no estoy mal ")
                || norm.contains(" no me paso nada ") || norm.contains(" no me pasó nada ")
                || norm.matches(".*\\b(si|sí)\\b.*");
    }

    private static boolean saysHelp(String norm) {
        return norm.contains(" no estoy bien ")
                || norm.contains(" no esta bien ")
                || norm.contains(" estoy mal ")
                || norm.contains(" me duele ")
                || norm.contains(" me lastime ") || norm.contains(" me lastimé ")
                || norm.contains(" no me puedo mover ")
                || norm.contains(" no puedo levantarme ") || norm.contains(" no puedo pararme ")
                || norm.contains(" ayuda ") || norm.contains(" ayudame ") || norm.contains(" ayúdame ")
                || norm.contains(" auxilio ") || norm.contains(" emergencia ") || norm.contains(" ambulancia ")
                || norm.contains(" doctor ") || norm.contains(" medico ") || norm.contains(" médico ");
    }

    private static boolean mentionsFall(String norm) {
        return norm.contains(" me cai ") || norm.contains(" me caí ")
                || norm.contains(" me caigo ") || norm.contains(" me estoy cayendo ")
                || norm.contains(" caida ") || norm.contains(" caída ")
                || norm.contains(" me tropece ") || norm.contains(" me tropecé ")
                || norm.contains(" me pegue ") || norm.contains(" me pegué ")
                || norm.contains(" me desmaye ") || norm.contains(" me desmayé ");
    }

    // --- VAD adaptativo y parser WAV robusto ---
    private static class WavInfo {
        int sampleRate;
        int channels;
        int bitsPerSample;
        long dataOffset;
        long dataSize;
    }

    private static boolean hasEnoughVoice(File wavFile, double cfgGateDbfs, int minVoicedMs) {
        try (FileInputStream in = new FileInputStream(wavFile)) {
            WavInfo wi = parseWav(in);
            if (wi == null || wi.bitsPerSample <= 0 || wi.channels <= 0 || wi.sampleRate <= 0) {
                Log.w(TAG, "VAD: formato WAV no reconocido → no bloqueo STT");
                return true;
            }
            in.getChannel().position(wi.dataOffset);

            final int FRAME_MS = 10;
            final int samplesPerFramePerCh = wi.sampleRate * FRAME_MS / 1000;
            final int bytesPerSample = Math.max(1, wi.bitsPerSample / 8);
            final int frameBytes = samplesPerFramePerCh * wi.channels * bytesPerSample;

            byte[] buf = new byte[frameBytes];
            int read;

            final int CAL_FRAMES = Math.min(30, (int)(wi.dataSize / frameBytes));
            double[] firstDb = new double[Math.max(1, CAL_FRAMES)];
            int idx = 0;

            while (idx < CAL_FRAMES && (read = in.read(buf)) == frameBytes) {
                firstDb[idx++] = frameDbfs(buf, wi.channels, bytesPerSample);
            }
            if (idx == 0) {
                Log.d(TAG, "VAD: archivo sin frames de audio");
                return false;
            }
            double[] slice = Arrays.copyOf(firstDb, idx);
            Arrays.sort(slice);
            double noiseDbfs = slice[(int)Math.floor(slice.length * 0.7)];

            double thr = Math.min(noiseDbfs + 6.0, cfgGateDbfs + 2.0);
            thr = Math.max(thr, -65.0);
            thr = Math.min(thr, -38.0);

            in.getChannel().position(wi.dataOffset);

            int voicedMs = 0;
            int continuousMs = 0;
            boolean peakDetected = false;

            long framesSeen = 0;

            while ((read = in.read(buf)) == frameBytes) {
                framesSeen++;
                double db = frameDbfs(buf, wi.channels, bytesPerSample);

                if (!peakDetected && framePeak(buf, wi.channels, bytesPerSample) > 0.10) {
                    peakDetected = true;
                }

                if (db > thr) {
                    voicedMs += FRAME_MS;
                    continuousMs += FRAME_MS;
                } else {
                    continuousMs = Math.max(0, continuousMs - FRAME_MS);
                }

                if (continuousMs >= 120) {
                    Log.d(TAG, "VAD pass por racha continua >=120ms");
                    break;
                }
            }

            boolean pass = (voicedMs >= minVoicedMs) || (continuousMs >= 120) || peakDetected;
            Log.d(TAG, String.format(Locale.US,
                    "VAD dbg → wav[%d Hz, %d ch, %d bits], noise=%.1f dBFS, thr=%.1f dBFS, voiced=%dms, cont=%dms, peak=%s, frames=%d",
                    wi.sampleRate, wi.channels, wi.bitsPerSample, noiseDbfs, thr, voicedMs, continuousMs,
                    peakDetected ? "Y" : "N", framesSeen));

            return pass;
        } catch (Exception e) {
            Log.w(TAG, "VAD error (" + e.getMessage() + ") → no bloqueo STT", e);
            return true;
        }
    }

    private static WavInfo parseWav(FileInputStream in) throws IOException {
        byte[] hdr12 = new byte[12];
        if (in.read(hdr12) != 12) return null;
        if (!(hdr12[0]=='R' && hdr12[1]=='I' && hdr12[2]=='F' && hdr12[3]=='F'
                && hdr12[8]=='W' && hdr12[9]=='A' && hdr12[10]=='V' && hdr12[11]=='E')) {
            return null;
        }
        WavInfo wi = new WavInfo();
        boolean haveFmt = false;
        boolean haveData = false;

        while (true) {
            byte[] chdr = new byte[8];
            int r = in.read(chdr);
            if (r < 8) break;
            String id = new String(chdr, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = ((chdr[4] & 0xFF)) | ((chdr[5] & 0xFF) << 8) | ((chdr[6] & 0xFF) << 16) | ((chdr[7] & 0xFF) << 24);
            if ("fmt ".equals(id)) {
                byte[] fmt = new byte[size];
                if (in.read(fmt) != size) return null;
                int audioFormat   = (fmt[0] & 0xFF) | ((fmt[1] & 0xFF) << 8);
                int channels      = (fmt[2] & 0xFF) | ((fmt[3] & 0xFF) << 8);
                int sampleRate    = (fmt[4] & 0xFF) | ((fmt[5] & 0xFF) << 8) | ((fmt[6] & 0xFF) << 16) | ((fmt[7] & 0xFF) << 24);
                int bitsPerSample = (fmt[14] & 0xFF) | ((fmt[15] & 0xFF) << 8);
                wi.sampleRate = sampleRate;
                wi.channels = Math.max(1, channels);
                wi.bitsPerSample = Math.max(8, bitsPerSample);
                haveFmt = (audioFormat == 1);
            } else if ("data".equals(id)) {
                wi.dataOffset = in.getChannel().position();
                wi.dataSize = size;
                haveData = true;
                break;
            } else {
                long cur = in.getChannel().position();
                in.getChannel().position(cur + size);
            }
        }

        if (!haveFmt || !haveData) return null;
        return wi;
    }

    private static double frameDbfs(byte[] buf, int channels, int bytesPerSample) {
        int samples = buf.length / bytesPerSample;
        int frames = samples / channels;
        double sumSq = 0.0;
        int count = 0;

        for (int i = 0; i < frames; i++) {
            double acc = 0.0;
            for (int ch = 0; ch < channels; ch++) {
                int index = (i * channels + ch) * bytesPerSample;
                double v = sampleToFloat(buf, index, bytesPerSample);
                acc += v;
            }
            double mono = acc / channels;
            sumSq += mono * mono;
            count++;
        }
        double rms = Math.sqrt(sumSq / Math.max(1, count)) + 1e-12;
        return 20.0 * Math.log10(rms);
    }

    private static double framePeak(byte[] buf, int channels, int bytesPerSample) {
        int samples = buf.length / bytesPerSample;
        int frames = samples / channels;
        double peak = 0.0;
        for (int i = 0; i < frames; i++) {
            double acc = 0.0;
            for (int ch = 0; ch < channels; ch++) {
                int index = (i * channels + ch) * bytesPerSample;
                double v = sampleToFloat(buf, index, bytesPerSample);
                acc += v;
            }
            double mono = Math.abs(acc / channels);
            if (mono > peak) peak = mono;
        }
        return peak;
    }

    private static double sampleToFloat(byte[] buf, int index, int bytesPerSample) {
        // little-endian
        switch (bytesPerSample) {
            case 1: // 8-bit unsigned PCM
                int u = buf[index] & 0xFF;
                return ((u - 128) / 128.0);
            case 2: // 16-bit signed PCM
                int lo = buf[index] & 0xFF;
                int hi = buf[index + 1];
                short s = (short)((hi << 8) | lo);
                return s / 32768.0;
            case 3: { // 24-bit signed PCM
                int b0 = buf[index] & 0xFF;
                int b1 = buf[index + 1] & 0xFF;
                int b2 = buf[index + 2];
                int v = (b2 << 16) | (b1 << 8) | b0;
                if ((v & 0x00800000) != 0) v |= 0xFF000000; // sign extend
                return v / 8388608.0;
            }
            case 4: { // 32-bit signed PCM
                int b0 = buf[index] & 0xFF;
                int b1 = buf[index + 1] & 0xFF;
                int b2 = buf[index + 2] & 0xFF;
                int b3 = buf[index + 3];
                int v = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
                return v / 2147483648.0;
            }
            default:
                return 0.0;
        }
    }
    // --- fin VAD ---

    private void handleAlarmResult(DeviceActions.AlarmResult res, String when) {
        switch (res) {
            case SET_SILENT:
                sayViaWakeService("Listo, te pongo una alarma para las " + when + ".", 12000);
                break;
            case UI_ACTION_REQUIRED:
                sayViaWakeService("Te dejé una notificación para confirmar la alarma de las " + when + ".", 12000);
                break;
            default:
                sayViaWakeService("No pude crear la alarma. Fijate permisos del reloj.", 10000);
                break;
        }
    }

    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
                return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER);
            } catch (Throwable ignored) {}
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                String pkg = tm.getDefaultDialerPackage();
                return getPackageName().equals(pkg);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isDeviceLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            return km.isDeviceLocked() || km.isKeyguardLocked();
        } else {
            return km.isKeyguardLocked();
        }
    }

    private boolean placeDirectCall(String displayName, String number) {
        boolean isDialer = isDefaultDialer();

        if (isDialer) {
            boolean startedFg = startTempForeground("Llamando a " + (displayName == null ? "" : displayName));
            try {
                TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
                if (tm == null) return false;
                Uri uri = Uri.fromParts("tel", number, null);
                Bundle extras = new Bundle();
                tm.placeCall(uri, extras);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "placeCall falló", t);
                return false;
            } finally {
                stopTempForeground();
            }
        }

        try {
            Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(call);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "ACTION_CALL falló", t);
            return false;
        }
    }

    private boolean startTempForeground(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        TEMP_FG_CHANNEL_ID, "Toto (tarea en curso)", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Uso temporal para ejecutar acciones");
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, TEMP_FG_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Toto")
                    .setContentText(text == null ? "Ejecutando acción…" : text)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true);
            Notification n = b.build();

            startForeground(
                    TEMP_FG_NOTIFY_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            );
            return true;
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, "FGS phoneCall no permitido por Manifest (falta android:foregroundServiceType=\"phoneCall\").", iae);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo iniciar FGS temporal", t);
            return false;
        }
    }

    private void stopTempForeground() {
        try { stopForeground(STOP_FOREGROUND_DETACH); } catch (Throwable ignore) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(TEMP_FG_NOTIFY_ID);
        } catch (Throwable ignore) {}
    }

    private void showCallNotification(String name, @Nullable String number) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CALL_CHANNEL_ID, "Llamar contacto", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tocar para llamar");
            nm.createNotificationChannel(ch);
        }

        final boolean hasCallPerm =
                androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CALL_PHONE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

        Intent tapIntent;
        if (number != null && !number.isEmpty() && hasCallPerm) {
            tapIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
        } else if (number != null && !number.isEmpty()) {
            tapIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        } else {
            tapIntent = new Intent(Intent.ACTION_DIAL);
        }
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = (int) (System.currentTimeMillis() & 0xFFFFFF);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, reqCode, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String who = (name != null && !name.isEmpty()) ? name : "Contacto";

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Llamar a " + who)
                .setContentText(number != null && !number.isEmpty() ? number : "Tocar para abrir el marcador")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi);

        int notifyId = CALL_NOTIFY_ID_BASE + (who.hashCode() & 0x0FFF);
        nm.notify(notifyId, b.build());
    }

    private static String capitalizeFirst(String s, Locale loc) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(loc) + s.substring(1);
    }

    private String mapLegacyActionToIntent(InstructionRouter.Action a) {
        if (a == null) return "UNKNOWN";
        switch (a) {
            case QUERY_TIME: return "QUERY_TIME";
            case QUERY_DATE: return "QUERY_DATE";
            case SET_ALARM:  return "SET_ALARM";
            case CALL:       return "CALL";
            default:         return "UNKNOWN";
        }
    }

    private void sayViaWakeService(String text, int watchdogMs) {
        Intent say = new Intent(this, WakeWordService.class)
                .setAction(WakeWordService.ACTION_SAY)
                .putExtra("text", text);
        androidx.core.content.ContextCompat.startForegroundService(this, say);
        if (watchdogMs > 0) postWatchdog(watchdogMs);
    }

    private void postWatchdog(int ms) {
        new android.os.Handler(getMainLooper()).postDelayed(() ->
                sendBroadcast(new Intent(WakeWordService.ACTION_CMD_FINISHED)), ms);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { super.onDestroy(); }

    @Nullable
    private static int[] tryParseIsoToLocalHourMinute(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        String[] patterns = new String[] {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm"
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(p, Locale.US);
                if (!p.endsWith("XXX")) {
                    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                }
                Date d = sdf.parse(iso);
                if (d != null) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(d);
                    return new int[]{ cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE) };
                }
            } catch (ParseException ignored) { }
        }
        return null;
    }

    private static final class FallbackMessage {
        final String who; final String text;
        FallbackMessage(String who, String text) { this.who = who; this.text = text; }
    }

    @Nullable
    private static FallbackMessage fallbackExtractMessage(String raw) {
        if (raw == null) return null;
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[“”\"']", "")
                .replaceAll("\\s+", " ")
                .trim();

        String[] pats = new String[] {
                "\\b(?:mandale|manda|mandar|escribile|escribe|escribir|decile|decime|dile|avisale|avisa|avisar)(?:\\s+un\\s+mensaje)?\\s+a\\s+([a-z0-9ñáéíóúü\\s.-]{1,40})\\s*(?:que|de que|diciendole|diciendole que|:|–|-)\\s*(.+)$",
                "\\b(?:mandale|manda|escribile|decile|avisale)(?:\\s+por\\s+whatsapp)?\\s+a\\s+([a-z0-9ñáéíóúü\\s.-]{1,40})\\s+(.*)$",
                "\\b(?:mensaje|msj)\\s+a\\s+([a-z0-9ñáéíóúü\\s.-]{1,40})\\s*(?:que|:)?\\s*(.+)$"
        };

        for (String p : pats) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(s);
            if (m.find()) {
                String who = cleanPerson(m.group(1));
                String text = cleanMessage(m.groupCount() >= 2 ? m.group(2) : "");
                if (!who.isEmpty() && !text.isEmpty()) return new FallbackMessage(who, text);
            }
        }

        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("\\b(?:mandale|manda|escribile|decile|dile|avisale)\\s+a\\s+([a-z0-9ñáéíóúü\\s.-]{1,40})\\b")
                .matcher(s);
        if (m2.find()) {
            String who = cleanPerson(m2.group(1));
            if (!who.isEmpty()) return new FallbackMessage(who, "");
        }
        return null;
    }

    private static String cleanPerson(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?:\\s+por\\s+favor.*$)|(?:\\s+gracias.*$)|(?:\\s+ahora.*$)|(?:\\s+urgente.*$)|(?:\\s+ya.*$)", " ");
        s = s.replaceAll("[^a-z0-9ñáéíóúü\\s.-]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.startsWith("a") && s.length() >= 2 && "bcdfghjklmnñpqrstvwxyz".indexOf(s.charAt(1)) >= 0) {
            s = s.substring(1);
        }
        String[] tok = s.split("\\s+");
        int limit = Math.min(tok.length, 4);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < limit; i++) { if (i > 0) out.append(' '); out.append(tok[i]); }
        return out.toString();
    }

    private static String cleanMessage(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("(\\s+por\\s+favor.*$)|(\\s+gracias.*$)", "").trim();
        return s;
    }

    // ===== Helpers =====
    private static String safe(String s) { return (s == null) ? "null" : s; }

    private static String slotsToString(com.example.toto_app.network.NluRouteResponse.Slots s) {
        if (s == null) return "{}";
        return "{contact=" + safe(s.contact_query) +
                ", hour=" + s.hour +
                ", minute=" + s.minute +
                ", dt=" + safe(s.datetime_iso) +
                ", msg=" + safe(s.message_text) +
                ", app=" + safe(s.app_name) + "}";
    }

    private static boolean looksLikeAlarmRequest(String raw) {
        if (raw == null) return false;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[¿?¡!.,;:()\\[\\]\"]", " ");
        s = " " + s.replaceAll("\\s+", " ").trim() + " ";
        return s.contains(" alarma ")
                || s.contains(" despert")
                || s.contains(" pone una alarma ")
                || s.contains(" poneme una alarma ")
                || s.contains(" pone alarma ")
                || s.contains(" poneme alarma ")
                || s.contains(" programa una alarma ")
                || s.contains(" programame una alarma ");
    }

    // Sanitizador de texto para TTS
    private static String sanitizeForTTS(String s) {
        if (s == null) return "";
        String out = s;

        out = out.replaceAll("(?s)```.*?```", " ");
        out = out.replace("`", "");
        out = out.replaceAll("!\\[(.*?)\\]\\((.*?)\\)", "$1");
        out = out.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "$1");
        out = out.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        out = out.replaceAll("(?m)^\\s*>\\s?", "");

        out = out.replaceAll("(?m)^\\s*([-*+]|•)\\s+", "— ");
        out = out.replaceAll("(?m)^\\s*(\\d+)[\\.)]\\s+", "$1: ");
        out = out.replace(" - ", ", ");
        out = out.replaceAll("\\((.*?)\\)", ", $1, ");
        out = out.replace(":", ", ");

        out = out.replaceAll("\\r?\\n\\s*\\r?\\n", " … ");
        out = out.replaceAll("\\r?\\n", " … ");

        out = out.replaceAll("\\s{2,}", " ").trim();

        out = ensureSpanishOpeners(out);

        if (!out.matches(".*[\\.!?…]$")) out = out + ".";
        return out;
    }

    private static String ensureSpanishOpeners(String text) {
        String[] parts = text.split("(?<=[\\.\\!\\?…])\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.endsWith("?") && !p.startsWith("¿")) parts[i] = "¿" + p;
            else if (p.endsWith("!") && !p.startsWith("¡")) parts[i] = "¡" + p;
            else parts[i] = p;
        }
        return String.join(" ", parts);
    }

    private static boolean looksLikePhoneNumber(String s) {
        if (s == null) return false;
        String t = s.replaceAll("[^0-9+]", "");
        return t.length() >= 6;
    }

    private static String normalizeDialable(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9+]", "");
    }
}
