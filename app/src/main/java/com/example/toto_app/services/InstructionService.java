package com.example.toto_app.services;

import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.toto_app.actions.DeviceActions;
import com.example.toto_app.audio.InstructionCapture;
import com.example.toto_app.audio.VadUtils;
import com.example.toto_app.calls.AppState;
import com.example.toto_app.calls.PhoneCallExecutor;
import com.example.toto_app.falls.FallLogic;
import com.example.toto_app.falls.FallSignals;
import com.example.toto_app.network.AskRequest;
import com.example.toto_app.network.AskResponse;
import com.example.toto_app.network.NluRouteResponse;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.SpotifyRepeatRequest;
import com.example.toto_app.network.SpotifyResponse;
import com.example.toto_app.network.SpotifyShuffleRequest;
import com.example.toto_app.network.SpotifyStatus;
import com.example.toto_app.network.SpotifyVolumeRequest;
import com.example.toto_app.nlp.NluResolver;
import com.example.toto_app.stt.SttClient;
import com.example.toto_app.util.TtsSanitizer;
import com.example.toto_app.util.UserDataManager;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;

import retrofit2.Response;

public class InstructionService extends android.app.Service {

    private static final String TAG = "InstructionService";

    // Bandera global simple para saber si hay conversación activa (grabación/STT/NLU en curso)
    private static volatile boolean sConversationActive = false;
    public static boolean isConversationActive() { return sConversationActive; }

    private UserDataManager userDataManager;

    private static final String EXTRA_FALL_MODE = "fall_mode";
    private boolean confirmWhatsApp = false;
    @Nullable private String fallMode = null;
    private int fallRetry = 0;
    private boolean fallOwner = false;

    @Override 
    public void onCreate() {
        super.onCreate();
        userDataManager = new UserDataManager(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // marca conversación activa mientras este servicio esté vivo
        sConversationActive = true;

        if (intent != null) {
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
            if (intent.hasExtra("confirm_whatsapp")) {
                confirmWhatsApp = intent.getBooleanExtra("confirm_whatsapp", false);
            }
        }

        // If this is a WhatsApp confirm flow, we don't activate fall signals; otherwise keep existing behavior
        if (fallMode != null && !confirmWhatsApp) {
            if (!FallSignals.isActive()) {
                FallSignals.tryActivate();
            }
            fallOwner = true;
        } else if (fallMode != null && confirmWhatsApp) {
            // confirm flow: don't touch FallSignals, but mark owner=false
            fallOwner = false;
        } else {
            if (FallSignals.isActive() && !confirmWhatsApp) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        Executors.newSingleThreadExecutor().execute(this::doWork);
        return START_NOT_STICKY;
    }

    private void doWork() {
        if (fallMode == null && FallSignals.isActive()) {
            stopSelf();
            return;
        }

        if ("CHECK".equals(fallMode)) {
            String prompt = "Escuché un golpe. ¿Estás bien?";
            sayThenListenHere(prompt, "AWAIT:0");
            stopSelf();
            return;
        }

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

        int minVoicedMs =
                ("AWAIT".equals(fallMode) || "AWAIT_ACTION".equals(fallMode) || "CHECK".equals(fallMode))
                        ? 220 : 320;

        if (!VadUtils.hasEnoughVoice(wav, cfg.silenceDbfs, minVoicedMs)) {
            Log.d(TAG, "VAD: silencio o voz insuficiente");
            if ("AWAIT".equals(fallMode)) {
                if (fallRetry <= 0) {
                    sayThenListenHere("No te escuché. ¿Estás bien?", "AWAIT:1");
                } else {
                    int res = sendEmergencyToAllContacts();
                    if (res == 0) sayViaWakeService("No te escuché. Ya avisé a tus contactos de emergencia.", 0);
                    else if (res == 1) sayViaWakeService("No hay conexión al servidor. En cuanto vuelva la conexión enviaré el mensaje de emergencia.", 0);
                    else sayViaWakeService("No te escuché. Tuve algunos inconvenientes para avisar a tus contactos.", 0);
                    if (fallOwner) {
                        FallSignals.clear();
                        Intent resume = new Intent(this, WakeWordService.class)
                                .setAction(WakeWordService.ACTION_RESUME_LISTEN)
                                .putExtra(WakeWordService.EXTRA_REASON, WakeWordService.REASON_FALL_CLEAR);
                        androidx.core.content.ContextCompat.startForegroundService(this, resume);
                    }
                }
                try { wav.delete(); } catch (Exception ignore) {}
                stopSelf();
                return;
            } else {
                // If this was a WhatsApp confirm flow, treat lack of voice as negative/absence
                if (confirmWhatsApp) {
                    IncomingMessageStore.get().clear();
                    try {
                        Intent handled = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_WHATSAPP_HANDLED);
                        androidx.core.content.ContextCompat.startForegroundService(this, handled);
                    } catch (Exception ignored) {}
                    try {
                        Intent resume = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_RESUME_LISTEN);
                        androidx.core.content.ContextCompat.startForegroundService(this, resume);
                    } catch (Exception ignored) {}
                    try { wav.delete(); } catch (Exception ignore) {}
                    stopSelf();
                    return;
                }

                sayViaWakeService("No te escuché bien.", 0);
                try { wav.delete(); } catch (Exception ignore) {}
                stopSelf();
                return;
            }
        }

        String transcript = "";
        try {
            transcript = SttClient.transcribe(wav);
        } finally {
            try { wav.delete(); } catch (Exception ignored) {}
        }

        if ("AWAIT".equals(fallMode)) {
            String norm = FallLogic.normEs(transcript);
            if (norm.isEmpty()) {
                if (fallRetry <= 0) {
                    sayThenListenHere("No te escuché. ¿Estás bien?", "AWAIT:1");
                } else {
                    int res = sendEmergencyToAllContacts();
                    if (res == 0) sayViaWakeService("No te escuché. Ya avisé a tus contactos de emergencia.", 0);
                    else if (res == 1) sayViaWakeService("No hay conexión al servidor. En cuanto vuelva la conexión enviaré el mensaje de emergencia.", 0);
                    else sayViaWakeService("No te escuché. Tuve algunos inconvenientes para avisar a tus contactos.", 0);
                    if (fallOwner) {
                        FallSignals.clear();
                        Intent resume = new Intent(this, WakeWordService.class)
                                .setAction(WakeWordService.ACTION_RESUME_LISTEN)
                                .putExtra(WakeWordService.EXTRA_REASON, WakeWordService.REASON_FALL_CLEAR);
                        androidx.core.content.ContextCompat.startForegroundService(this, resume);
                    }
                }
                stopSelf();
                return;
            }

            FallLogic.FallReply fr = FallLogic.assessFallReply(norm);
            switch (fr) {
                case HELP: {
                    int res = sendEmergencyToAllContacts();
                    if (res == 0) sayViaWakeService("Ya avisé a tus contactos de emergencia.", 0);
                    else if (res == 1) sayViaWakeService("No hay conexión al servidor. En cuanto vuelva la conexión enviaré el mensaje de emergencia.", 0);
                    else sayViaWakeService("Tuve algunos inconvenientes para avisar a tus contactos.", 0);
                    if (fallOwner) {
                        FallSignals.clear();
                        Intent resume = new Intent(this, WakeWordService.class)
                                .setAction(WakeWordService.ACTION_RESUME_LISTEN)
                                .putExtra(WakeWordService.EXTRA_REASON, WakeWordService.REASON_FALL_CLEAR);
                        androidx.core.content.ContextCompat.startForegroundService(this, resume);
                    }
                    stopSelf(); return;
                }
                case OK: {
                    sayViaWakeService("Me alegro. Si necesitás ayuda, decime.", 0);
                    if (fallOwner) {
                        FallSignals.clear();
                        Intent resume = new Intent(this, WakeWordService.class)
                                .setAction(WakeWordService.ACTION_RESUME_LISTEN)
                                .putExtra(WakeWordService.EXTRA_REASON, WakeWordService.REASON_FALL_CLEAR);
                        androidx.core.content.ContextCompat.startForegroundService(this, resume);
                    }
                    stopSelf(); return;
                }
                case UNKNOWN:
                default: {
                    sayThenListenHere("No me quedó claro. ¿Estás bien?", "AWAIT:" + fallRetry);
                    stopSelf(); return;
                }
            }
        }

        if (transcript.isEmpty()) {
            // For WhatsApp confirm flows, treat empty transcript as absence -> clear and re-arm
            if (confirmWhatsApp) {
                IncomingMessageStore.get().clear();
                try {
                    Intent handled = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_WHATSAPP_HANDLED);
                    androidx.core.content.ContextCompat.startForegroundService(this, handled);
                } catch (Exception ignored) {}
                try {
                    Intent resume = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_RESUME_LISTEN);
                    androidx.core.content.ContextCompat.startForegroundService(this, resume);
                } catch (Exception ignored) {}
                stopSelf();
                return;
            }

            sayViaWakeService("No te escuché bien.", 0);
            stopSelf();
            return;
        }

        String normAll = FallLogic.normEs(transcript);
        if (FallLogic.saysHelp(normAll) || FallLogic.mentionsFall(normAll)) {
            sayThenListenHere("¿Estás bien?", "AWAIT:0");
            stopSelf();
            return;
        }

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
                IncomingMessageStore.Msg m = IncomingMessageStore.get().peek();
                if (m != null) {
                    String who = (m.from == null ? "alguien" : m.from);
                    // Por defecto, límite de cuántos leer ahora. Elegimos 5 para no ser engorroso.
                    final int READ_LIMIT = 5;
                    String tts;
                    List<String> toMark = null;

                    if (m.parts != null && !m.parts.isEmpty()) {
                        // filtrar los que aún no se leyeron y tomar los últimos hasta READ_LIMIT
                        List<String> freshParts = IncomingMessageStore.get().filterNewParts(who, m.parts, READ_LIMIT);
                        if (!freshParts.isEmpty()) {
                            toMark = new ArrayList<>(freshParts);
                            // Construir TTS: "Nombre dice: msg1. msg2. msg3"
                            StringBuilder sb = new StringBuilder();
                            sb.append(who).append(" dice: ");
                            for (int i = 0; i < freshParts.size(); i++) {
                                if (i > 0) sb.append(". ");
                                sb.append(freshParts.get(i));
                            }
                            tts = sb.toString();
                        } else {
                            // No hay partes nuevas -> caer a body completo (compat) una vez
                            tts = who + " dice: " + (m.body == null ? "…" : m.body);
                        }
                    } else {
                        // Sin partes, usar cuerpo plano
                        tts = who + " dice: " + (m.body == null ? "…" : m.body);
                    }

                    // Consumimos el store (para cerrar flujo de confirmación) antes de hablar
                    IncomingMessageStore.get().consume();
                    // Marcar como leídas las partes efectivamente leídas
                    if (toMark != null && !toMark.isEmpty()) {
                        IncomingMessageStore.get().markSpoken(who, toMark);
                    }

                    // Notify WakeWordService that WhatsApp confirm has been handled (clear pending flag)
                    try {
                        Intent handled = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_WHATSAPP_HANDLED);
                        androidx.core.content.ContextCompat.startForegroundService(this, handled);
                    } catch (Exception ignored) {}

                    sayViaWakeService(tts, 0);

                    // After reading, re-arm wake listening
                    try {
                        Intent resume = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_RESUME_LISTEN);
                        androidx.core.content.ContextCompat.startForegroundService(this, resume);
                    } catch (Exception ignored) {}

                    stopSelf();
                    return;
                }
            }

            // If this is a WhatsApp confirm flow and we reached here without reading,
            // treat it as a 'no' / absence of response: clear the pending incoming and notify service
            if (confirmWhatsApp && IncomingMessageStore.get().isAwaitingConfirm() && !saysAffirm && !saysRead) {
                // clear stored incoming (do not read)
                IncomingMessageStore.get().clear();
                try {
                    Intent handled = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_WHATSAPP_HANDLED);
                    androidx.core.content.ContextCompat.startForegroundService(this, handled);
                } catch (Exception ignored) {}

                // re-arm wake listening so Toto continues normal operation
                try {
                    Intent resume = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_RESUME_LISTEN);
                    androidx.core.content.ContextCompat.startForegroundService(this, resume);
                } catch (Exception ignored) {}

                stopSelf();
                return;
            }
        } catch (Throwable ignored) {}

        NluRouteResponse nres = NluResolver.resolveWithFallback(transcript);
        String intentName = (nres != null && nres.intent != null)
                ? nres.intent.trim().toUpperCase(java.util.Locale.ROOT) : "UNKNOWN";

        if (nres != null) {
            Log.d(TAG, "NLU/route → intent=" + safe(nres.intent)
                    + " conf=" + nres.confidence
                    + " needsConf=" + nres.needs_confirmation
                    + " slots=" + slotsToString(nres.slots)
                    + " ack=" + safe(nres.ack_tts));
        } else {
            Log.d(TAG, "NLU/route → nres=null");
        }

        if (nres != null && nres.ack_tts != null && !nres.ack_tts.trim().isEmpty()) {
            boolean isAnswerish = "ANSWER".equals(intentName) || "UNKNOWN".equals(intentName);
            boolean isSpotifyPlay = "SPOTIFY_PLAY".equals(intentName);
            boolean isFall = "FALL".equals(intentName);
            if (!"QUERY_TIME".equals(intentName) && !"QUERY_DATE".equals(intentName)
                    && !isAnswerish && !"CALL".equals(intentName)
                    && !"SEND_MESSAGE".equals(intentName)
                    && !isSpotifyPlay
                    && !isFall) {
                if (!FallSignals.isActive()) {
                    sayViaWakeService(TtsSanitizer.sanitizeForTTS(nres.ack_tts), 0);
                } else {
                    stopSelf(); return;
                }
            }
        }

        if (nres != null && nres.needs_confirmation
                && nres.clarifying_question != null
                && !nres.clarifying_question.trim().isEmpty()) {

            boolean actionable =
                    "SET_ALARM".equals(intentName) ||
                            "CALL".equals(intentName) ||
                            "SEND_MESSAGE".equals(intentName) ||
                            "SPOTIFY_PLAY".equals(intentName);

            if (actionable) {
                if (!FallSignals.isActive()) {
                    sayViaWakeService(TtsSanitizer.sanitizeForTTS(nres.clarifying_question.trim()), 0);
                }
                stopSelf();
                return;
            }
        }

        switch (intentName) {
            case "FALL": {
                if (!FallSignals.isActive()) {
                    FallSignals.tryActivate();
                    fallOwner = true;
                }
                sayThenListenHere("¿Estás bien?", "AWAIT:0");
                stopSelf(); return;
            }

            case "QUERY_TIME": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                Calendar c = Calendar.getInstance();
                sayViaWakeService("Son las " + DeviceActions.hhmm(
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + ".", 0);
                stopSelf(); return;
            }
            case "QUERY_DATE": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                Locale esAR = new Locale("es", "AR");
                Calendar c = Calendar.getInstance();
                SimpleDateFormat fmt = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", esAR);
                String pretty = capitalizeFirst(fmt.format(c.getTime()), esAR);
                sayViaWakeService("Hoy es " + pretty + ".", 0);
                stopSelf(); return;
            }
            case "SET_ALARM": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                Integer hh = (nres != null && nres.slots != null) ? nres.slots.hour : null;
                Integer mm = (nres != null && nres.slots != null) ? nres.slots.minute : null;

                if ((hh == null || mm == null) && nres != null && nres.slots != null
                        && nres.slots.datetime_iso != null && !nres.slots.datetime_iso.isEmpty()) {
                    int[] hm = tryParseIsoToLocalHourMinute(nres.slots.datetime_iso);
                    if (hm != null) { hh = hm[0]; mm = hm[1]; }
                }
                if (hh == null || mm == null) {
                    sayViaWakeService("¿Para qué hora querés la alarma?", 0);
                    stopSelf(); return;
                }
                // Si el backend está caído, no permitimos crear alarmas (según solicitud)
                try {
                    boolean backendUp = com.example.toto_app.services.BackendHealthManager.get().isBackendUp();
                    if (!backendUp) {
                        sayViaWakeService("No puedo configurar la alarma ahora porque no hay conexión al servidor.", 0);
                        stopSelf(); return;
                    }
                } catch (Throwable ignored) {
                    // Si no podemos consultar, ser conservadores y denegar
                    sayViaWakeService("No puedo configurar la alarma ahora porque no hay conexión al servidor.", 0);
                    stopSelf(); return;
                }
                DeviceActions.AlarmResult res = DeviceActions.setAlarm(this, hh, mm, "Toto");
                handleAlarmResult(res, DeviceActions.hhmm(hh, mm));
                stopSelf(); return;
            }

            case "CALL": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                String who = (nres != null && nres.slots != null) ? nres.slots.contact_query : null;
                if (who == null || who.trim().isEmpty()) {
                    sayViaWakeService("¿A quién querés que llame?", 0);
                    stopSelf(); return;
                }

                boolean hasContacts = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.READ_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasContacts) {
                    sayViaWakeService("Necesito permiso de contactos para llamar por nombre. Abrí la app para darlo.", 0);
                    Intent perm = new Intent(this, com.example.toto_app.MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("request_contacts_perm", true);
                    startActivity(perm);
                    postWatchdog(8000);
                    stopSelf(); return;
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
                    NotificationService.showCallNotification(this, who, null,
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.CALL_PHONE)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED);
                    sayViaWakeService("No encontré a " + who + " en tus contactos. Te dejé una notificación para marcar.", 0);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                if (!AppState.isAppInForeground(this)) {
                    NotificationService.showCallNotification(this, rc.name, rc.number,
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.CALL_PHONE)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED);
                    sayViaWakeService("Tocá la notificación para llamar a " + rc.name + ".", 0);
                    postWatchdog(8000);
                    stopSelf(); return;
                }
                if (AppState.isDeviceLocked(this)) {
                    NotificationService.showCallNotification(this, rc.name, rc.number,
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.CALL_PHONE)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED);
                    sayViaWakeService("Desbloqueá y tocá la notificación para llamar a " + rc.name + ".", 0);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                boolean hasCall = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CALL_PHONE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasCall && !AppState.isDefaultDialer(this)) {
                    NotificationService.showCallNotification(this, rc.name, rc.number, hasCall);
                    sayViaWakeService("Tocá la notificación para llamar a " + rc.name + ".", 0);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                PhoneCallExecutor calls = new PhoneCallExecutor(this);
                boolean ok = calls.placeDirectCall(rc.name, rc.number, AppState.isDefaultDialer(this));
                if (ok) sayViaWakeService("Llamando a " + rc.name + ".", 0);
                else {
                    NotificationService.showCallNotification(this, rc.name, rc.number, hasCall);
                    sayViaWakeService("No pude iniciar la llamada directa. Te dejé una notificación para marcar.", 0);
                }

                postWatchdog(8000);
                stopSelf(); return;
            }

            case "SEND_MESSAGE": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                String who = (nres != null && nres.slots != null) ? nres.slots.contact_query : null;
                String msg = (nres != null && nres.slots != null) ? nres.slots.message_text : null;

                if ((who == null || who.isBlank()) || (msg == null || msg.isBlank())) {
                    FallbackMessage fm = fallbackExtractMessage(transcript);
                    if (who == null || who.isBlank()) who = (fm != null ? fm.who : null);
                    if (msg == null || msg.isBlank()) msg = (fm != null ? fm.text : null);
                }

                if (who == null || who.trim().isEmpty()) {
                    sayViaWakeService("¿A quién querés mandarle el mensaje?", 0);
                    stopSelf(); return;
                }

                boolean hasContacts = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.READ_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasContacts) {
                    sayViaWakeService("Necesito permiso de contactos para mandar por nombre. Abrí la app para darlo.", 0);
                    Intent perm = new Intent(this, com.example.toto_app.MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("request_contacts_perm", true);
                    startActivity(perm);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                DeviceActions.ResolvedContact rc = DeviceActions.resolveContactByNameFuzzy(this, who);
                if (rc == null || rc.number == null || rc.number.isEmpty()) {
                    sayViaWakeService("No encontré a " + who + " en tus contactos.", 0);
                    stopSelf(); return;
                }

                if (msg == null || msg.trim().isEmpty()) {
                    sayViaWakeService("¿Qué querés que le diga a " + rc.name + "?", 0);
                    stopSelf(); return;
                }

                String to = rc.number.replaceAll("[^0-9+]", "");
                try {
                    com.example.toto_app.network.WhatsAppSendRequest wreq =
                            new com.example.toto_app.network.WhatsAppSendRequest(to, msg.trim(), Boolean.FALSE);
                    retrofit2.Response<com.example.toto_app.network.WhatsAppSendResponse> wresp =
                            RetrofitClient.api().waSend(wreq).execute();

                    com.example.toto_app.network.WhatsAppSendResponse wbody = wresp.body();
                    boolean ok =
                            wresp.isSuccessful()
                                    && wbody != null
                                    && ("ok".equalsIgnoreCase(wbody.status)
                                    || "ok_template".equalsIgnoreCase(wbody.status)
                                    || (wbody.id != null && !wbody.id.trim().isEmpty()));

                    if (ok) sayViaWakeService("Listo, le mandé el mensaje a " + rc.name + ".", 0);
                    else {
                        String err = null;
                        try { err = (wresp.errorBody() != null) ? wresp.errorBody().string() : null; } catch (Exception ignored) {}
                        Log.e(TAG, "WA send failed: HTTP=" + (wresp != null ? wresp.code() : -1)
                                + " status=" + (wbody != null ? wbody.status : "null")
                                + " id=" + (wbody != null ? wbody.id : "null")
                                + " err=" + err);
                        if (err != null && (err.contains("recipient_not_allowed") || err.contains("131030")))
                            sayViaWakeService("No pude enviar por WhatsApp porque ese número no está autorizado aún.", 0);
                        else
                            sayViaWakeService("No pude mandar el mensaje ahora.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/whatsapp/send", ex);
                    sayViaWakeService("Tuve un problema mandando el mensaje.", 0);
                }

                stopSelf(); return;
            }

            case "SPOTIFY_PLAY": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                String query = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (query == null || query.isBlank()) {
                    sayViaWakeService("¿Qué querés escuchar en Spotify?", 0);
                    stopSelf(); return;
                }

                try {
                    retrofit2.Response<SpotifyStatus> s = RetrofitClient.api().spotifyStatus().execute();
                    SpotifyStatus st = s.isSuccessful() ? s.body() : null;
                    if (st == null) { sayViaWakeService("No pude verificar Spotify ahora.", 0); stopSelf(); return; }

                    if (!Boolean.TRUE.equals(st.connected)) {
                        String url = st.loginUrl;
                        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                                this, 1001, i,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                        NotificationService.simpleActionNotification(this, "Conectar Spotify",
                                "Tocá para vincular tu cuenta de Spotify.", pi);
                        sayViaWakeService("Necesito conectar tu Spotify. Tocá la notificación para autorizar.", 0);
                        postWatchdog(8000); stopSelf(); return;
                    }

                    if (st.premium != null && !st.premium) {
                        sayViaWakeService("Tu cuenta de Spotify no es Premium.", 0);
                        stopSelf(); return;
                    }

                    if (st.deviceCount == null || st.deviceCount == 0) {
                        boolean activated = ensureSpotifyDeviceActivated(query, /*timeoutMs=*/8000);
                        if (!activated) {
                            sayViaWakeService("No encuentro un dispositivo de Spotify. Abrí Spotify una vez y volvemos a intentar.", 0);
                            stopSelf(); return;
                        }
                    }

                    java.util.Map<String,String> body = new java.util.HashMap<>();
                    body.put("query", query);
                    if (st.suggestedDeviceId != null && !st.suggestedDeviceId.isEmpty()) {
                        body.put("deviceId", st.suggestedDeviceId);
                    }

                    retrofit2.Response<com.google.gson.JsonObject> r = RetrofitClient.api().spotifyPlay(body).execute();
                    if (r.isSuccessful()) {
                        signalCmdFinishedNow();
                        signalCmdFinishedLater(1500);
                    } else {
                        String err = null;
                        try { err = (r.errorBody() != null) ? r.errorBody().string() : null; } catch (Exception ignore) {}
                        String speak = "No pude reproducir en Spotify ahora.";
                        if (err != null) {
                            if (err.contains("NOT_LOGGED_IN")) speak = "Necesitás conectar tu Spotify.";
                            else if (err.contains("PREMIUM_REQUIRED")) speak = "Tu cuenta de Spotify no es Premium.";
                            else if (err.contains("NO_DEVICE")) speak = "No hay un dispositivo de Spotify activo.";
                            else if (err.contains("SEARCH_EMPTY")) speak = "No encontré ese tema en Spotify.";
                        }
                        sayViaWakeService(speak, 0);
                    }

                } catch (Exception ex) {
                    Log.e(TAG, "Spotify play error", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }

                stopSelf(); return;
            }

            case "SPOTIFY_PAUSE": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                try {
                    retrofit2.Response<SpotifyResponse> r = RetrofitClient.api().spotifyPause().execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 0);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude pausar Spotify.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/pause", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_RESUME": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                sayViaWakeService("No pude continuar la reproducción.", 0);
                stopSelf(); return;
            }

            case "SPOTIFY_NEXT": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                try {
                    retrofit2.Response<SpotifyResponse> r = RetrofitClient.api().spotifyNext().execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 0);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude pasar al siguiente.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/next", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_PREV": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                try {
                    retrofit2.Response<SpotifyResponse> r = RetrofitClient.api().spotifyPrev().execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 0);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude volver al anterior.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/prev", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_SET_VOLUME": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                String v = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (v == null || v.trim().isEmpty()) v = "up";
                try {
                    retrofit2.Response<SpotifyResponse> r =
                            RetrofitClient.api().spotifyVolume(new SpotifyVolumeRequest(v.trim())).execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 0);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude ajustar el volumen en Spotify.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/volume", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_SET_SHUFFLE": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                String state = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (state == null || state.isBlank()) state = "on";
                try {
                    retrofit2.Response<SpotifyResponse> r =
                            RetrofitClient.api().spotifyShuffle(new SpotifyShuffleRequest(state)).execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 0);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude cambiar el modo aleatorio.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/shuffle", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_SET_REPEAT": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                String state = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (state == null || state.isBlank()) state = "track";
                try {
                    retrofit2.Response<SpotifyResponse> r =
                            RetrofitClient.api().spotifyRepeat(new SpotifyRepeatRequest(state)).execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 0);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude cambiar el modo de repetición.", 0);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/repeat", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 0);
                }
                stopSelf(); return;
            }

            case "CANCEL": {
                if (FallSignals.isActive()) { stopSelf(); return; }
                sayViaWakeService("Listo.", 0);
                stopSelf(); return;
            }
            case "ANSWER":
            case "UNKNOWN":
            default: {
                if (FallSignals.isActive()) { stopSelf(); return; }
                boolean backendUp = true;
                try { backendUp = com.example.toto_app.services.BackendHealthManager.get().isBackendUp(); } catch (Throwable ignore) { backendUp = true; }
                if (!backendUp) {
                    // Cuando el backend está caído, evitamos decir "No estoy seguro" y ofrecemos intentar localmente
                    String offlineReply = "No hay conexión al servidor. Volvé a intentar más tarde.";
                    sayViaWakeService(TtsSanitizer.sanitizeForTTS(offlineReply), 0);
                    stopSelf(); return;
                }
                try {
                    AskRequest rq = new AskRequest();
                    rq.prompt = transcript;
                    rq.userId = userDataManager.getUserName();  // Enviar el nombre del usuario para memoria de conversación
                    Response<AskResponse> r2 = RetrofitClient.api().ask(rq).execute();
                    String reply = (r2.isSuccessful() && r2.body() != null && r2.body().reply != null)
                            ? r2.body().reply.trim()
                            : "No estoy seguro, ¿podés repetir?";
                    sayViaWakeService(TtsSanitizer.sanitizeForTTS(reply), 0);
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/ask", ex);
                    sayViaWakeService("Tuve un problema procesando eso.", 0);
                }
                stopSelf(); return;
            }
        }
    }

    private void sayThenListenHere(String text, @Nullable String nextFallMode) {
        Intent say = new Intent(this, WakeWordService.class)
                .setAction(WakeWordService.ACTION_SAY)
                .putExtra("text", TtsSanitizer.sanitizeForTTS(text))
                .putExtra(WakeWordService.EXTRA_AFTER_SAY_START_SERVICE, true)
                .putExtra(WakeWordService.EXTRA_AFTER_SAY_USER_NAME, userDataManager.getUserName());
        if (nextFallMode != null) {
            say.putExtra(WakeWordService.EXTRA_AFTER_SAY_FALL_MODE, nextFallMode);
        }
        androidx.core.content.ContextCompat.startForegroundService(this, say);
    }

    private void handleAlarmResult(DeviceActions.AlarmResult res, String when) {
        switch (res) {
            case SET_SILENT:
                sayViaWakeService("Listo, te pongo una alarma para las " + when + ".", 0);
                break;
            case UI_ACTION_REQUIRED:
                sayViaWakeService("Te dejé una notificación para confirmar la alarma de las " + when + ".", 0);
                break;
            default:
                sayViaWakeService("No pude crear la alarma. Fijate permisos del reloj.", 0);
                break;
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

    private void signalCmdFinishedNow() {
        Intent svc = new Intent(this, WakeWordService.class)
                .setAction(WakeWordService.ACTION_CMD_FINISHED)
                .putExtra("reason", "SPOTIFY_PLAY_OK");
        androidx.core.content.ContextCompat.startForegroundService(this, svc);

        sendBroadcast(new Intent(WakeWordService.ACTION_CMD_FINISHED)
                .putExtra("reason", "SPOTIFY_PLAY_OK"));
    }

    private void signalCmdFinishedLater(int delayMs) {
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            Intent svc = new Intent(this, WakeWordService.class)
                    .setAction(WakeWordService.ACTION_CMD_FINISHED)
                    .putExtra("reason", "SPOTIFY_PLAY_OK_DELAYED");
            androidx.core.content.ContextCompat.startForegroundService(this, svc);

            sendBroadcast(new Intent(WakeWordService.ACTION_CMD_FINISHED)
                    .putExtra("reason", "SPOTIFY_PLAY_OK_DELAYED"));
        }, Math.max(150, delayMs));
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        super.onDestroy();
        sConversationActive = false;
    }

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

    private static String capitalizeFirst(String s, Locale loc) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(loc) + s.substring(1);
    }

    private static String safe(String s) { return (s == null) ? "null" : s; }

    private static String slotsToString(NluRouteResponse.Slots s) {
        if (s == null) return "{}";
        return "{contact=" + safe(s.contact_query) +
                ", hour=" + s.hour +
                ", minute=" + s.minute +
                ", dt=" + safe(s.datetime_iso) +
                ", msg=" + safe(s.message_text) +
                ", app=" + safe(s.app_name) + "}";
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

    private static boolean isOk(retrofit2.Response<SpotifyResponse> r) {
        if (r == null) return false;
        if (!r.isSuccessful()) return false;
        SpotifyResponse b = r.body();
        if (b == null) return true;
        if (b.ok != null) return b.ok;
        if (b.status != null) return "ok".equalsIgnoreCase(b.status) || "success".equalsIgnoreCase(b.status);
        return true;
    }

    private boolean tryOpenSpotifyNow(String query) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("spotify:search:" + android.net.Uri.encode(query)));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "No pude abrir Spotify con URI (cae a abrir paquete): " + e.getMessage());
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback);
                    return true;
                }
            } catch (Exception e2) {
                Log.e(TAG, "No pude abrir Spotify app: ", e2);
            }
        }
        return false;
    }

    private boolean ensureSpotifyDeviceActivated(String query, int timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(2000, timeoutMs);

        boolean canLaunchNow = AppState.isAppInForeground(this) && !AppState.isDeviceLocked(this);
        if (canLaunchNow) {
            tryOpenSpotifyNow(query);
        } else {
            Intent open = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 1002, open,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                NotificationService.simpleActionNotification(
                        this, "Abrir Spotify", "Tocá para activar un dispositivo.", pi);
            }
        }

        while (SystemClock.uptimeMillis() < deadline) {
            try {
                retrofit2.Response<SpotifyStatus> s = RetrofitClient.api().spotifyStatus().execute();
                SpotifyStatus st = s.isSuccessful() ? s.body() : null;
                if (st != null && Boolean.TRUE.equals(st.connected)) {
                    Integer dc = st.deviceCount;
                    if (dc != null && dc > 0) return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Polling spotifyStatus: " + e.getMessage());
            }
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    /**
     * Send emergency message to all contacts (caregivers + trusted contacts).
     * Returns result code: 0=sent, 1=queued, 2=failed
     */
    private int sendEmergencyToAllContacts() {
        java.util.List<com.example.toto_app.network.EmergencyContactDTO> allContacts = 
                userDataManager.getAllEmergencyContacts();
        
        if (allContacts.isEmpty()) {
            Log.w(TAG, "No emergency contacts found");
            return 2;
        }
        
        java.util.List<String> phoneNumbers = new java.util.ArrayList<>();
        for (com.example.toto_app.network.EmergencyContactDTO contact : allContacts) {
            if (contact.getPhone() != null && !contact.getPhone().trim().isEmpty()) {
                phoneNumbers.add(contact.getPhone());
            }
        }
        
        return FallLogic.sendEmergencyMessageToMultiple(phoneNumbers, userDataManager.getUserName());
    }
}
