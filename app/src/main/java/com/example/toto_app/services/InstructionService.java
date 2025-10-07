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
import com.example.toto_app.network.AskRequest;
import com.example.toto_app.network.AskResponse;
import com.example.toto_app.network.NluRouteResponse;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.SpotifyStatus;
import com.example.toto_app.nlp.NluResolver;
import com.example.toto_app.stt.SttClient;
import com.example.toto_app.util.TtsSanitizer;
import com.example.toto_app.falls.FallLogic;

import com.example.toto_app.network.SpotifyResponse;
import com.example.toto_app.network.SpotifyVolumeRequest;
import com.example.toto_app.network.SpotifyShuffleRequest;
import com.example.toto_app.network.SpotifyRepeatRequest;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;

import retrofit2.Response;

public class InstructionService extends android.app.Service {

    private static final String TAG = "InstructionService";

    private String userName = "Juan";

    private static final String EXTRA_FALL_MODE = "fall_mode";
    @Nullable private String fallMode = null;
    private int fallRetry = 0;

    private static final String EMERGENCY_NAME   = "Tamara";
    private static final String EMERGENCY_NUMBER = "+5491159753115";

    @Override public void onCreate() { super.onCreate(); }

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
                    boolean ok = FallLogic.sendEmergencyMessageTo(EMERGENCY_NUMBER, userName);
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
                    boolean ok = FallLogic.sendEmergencyMessageTo(EMERGENCY_NUMBER, userName);
                    if (ok) sayViaWakeService("No te escuché. Ya avisé a " + EMERGENCY_NAME + ".", 10000);
                    else    sayViaWakeService("No te escuché y no pude avisar a " + EMERGENCY_NAME + ".", 12000);
                }
                stopSelf();
                return;
            }

            FallLogic.FallReply fr = FallLogic.assessFallReply(norm);
            switch (fr) {
                case HELP: {
                    boolean ok = FallLogic.sendEmergencyMessageTo(EMERGENCY_NUMBER, userName);
                    if (ok) sayViaWakeService("Ya avisé a " + EMERGENCY_NAME + ".", 8000);
                    else    sayViaWakeService("Quise avisar a " + EMERGENCY_NAME + " pero no pude enviar el mensaje.", 10000);
                    stopSelf(); return;
                }
                case OK: {
                    sayViaWakeService("Me alegro. Si necesitás ayuda, decime.", 5000);
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
            sayViaWakeService("No te escuché bien.", 6000);
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
                IncomingMessageStore.Msg m = IncomingMessageStore.get().consume();
                if (m != null) {
                    String tts = (m.from == null ? "alguien" : m.from) + " dice: " + (m.body == null ? "…" : m.body);
                    sayViaWakeService(tts, 8000);
                    stopSelf();
                    return;
                }
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
            if (!"QUERY_TIME".equals(intentName) && !"QUERY_DATE".equals(intentName)
                    && !isAnswerish && !"CALL".equals(intentName)
                    && !"SEND_MESSAGE".equals(intentName)
                    && !isSpotifyPlay) {
                sayViaWakeService(TtsSanitizer.sanitizeForTTS(nres.ack_tts), 6000);
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
                sayViaWakeService(TtsSanitizer.sanitizeForTTS(nres.clarifying_question.trim()), 8000);
                stopSelf();
                return;
            }
        }

        switch (intentName) {
            case "QUERY_TIME": {
                Calendar c = Calendar.getInstance();
                sayViaWakeService("Son las " + DeviceActions.hhmm(
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + ".", 6000);
                stopSelf(); return;
            }
            case "QUERY_DATE": {
                Locale esAR = new Locale("es", "AR");
                Calendar c = Calendar.getInstance();
                SimpleDateFormat fmt = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", esAR);
                String pretty = capitalizeFirst(fmt.format(c.getTime()), esAR);
                sayViaWakeService("Hoy es " + pretty + ".", 6000);
                stopSelf(); return;
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
                    stopSelf(); return;
                }
                DeviceActions.AlarmResult res = DeviceActions.setAlarm(this, hh, mm, "Toto");
                handleAlarmResult(res, DeviceActions.hhmm(hh, mm));
                stopSelf(); return;
            }

            case "CALL": {
                String who = (nres != null && nres.slots != null) ? nres.slots.contact_query : null;
                if (who == null || who.trim().isEmpty()) {
                    sayViaWakeService("¿A quién querés que llame?", 8000);
                    stopSelf(); return;
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
                    sayViaWakeService("No encontré a " + who + " en tus contactos. Te dejé una notificación para marcar.", 12000);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                if (!AppState.isAppInForeground(this)) {
                    NotificationService.showCallNotification(this, rc.name, rc.number,
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.CALL_PHONE)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED);
                    sayViaWakeService("Tocá la notificación para llamar a " + rc.name + ".", 10000);
                    postWatchdog(8000);
                    stopSelf(); return;
                }
                if (AppState.isDeviceLocked(this)) {
                    NotificationService.showCallNotification(this, rc.name, rc.number,
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.CALL_PHONE)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED);
                    sayViaWakeService("Desbloqueá y tocá la notificación para llamar a " + rc.name + ".", 12000);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                boolean hasCall = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CALL_PHONE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (!hasCall && !AppState.isDefaultDialer(this)) {
                    NotificationService.showCallNotification(this, rc.name, rc.number, hasCall);
                    sayViaWakeService("Tocá la notificación para llamar a " + rc.name + ".", 12000);
                    postWatchdog(8000);
                    stopSelf(); return;
                }

                PhoneCallExecutor calls = new PhoneCallExecutor(this);
                boolean ok = calls.placeDirectCall(rc.name, rc.number, AppState.isDefaultDialer(this));
                if (ok) sayViaWakeService("Llamando a " + rc.name + ".", 4000);
                else {
                    NotificationService.showCallNotification(this, rc.name, rc.number, hasCall);
                    sayViaWakeService("No pude iniciar la llamada directa. Te dejé una notificación para marcar.", 12000);
                }

                postWatchdog(8000);
                stopSelf(); return;
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
                    stopSelf(); return;
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
                    stopSelf(); return;
                }

                DeviceActions.ResolvedContact rc = DeviceActions.resolveContactByNameFuzzy(this, who);
                if (rc == null || rc.number == null || rc.number.isEmpty()) {
                    sayViaWakeService("No encontré a " + who + " en tus contactos.", 9000);
                    stopSelf(); return;
                }

                if (msg == null || msg.trim().isEmpty()) {
                    sayViaWakeService("¿Qué querés que le diga a " + rc.name + "?", 9000);
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

                    if (ok) sayViaWakeService("Listo, le mandé el mensaje a " + rc.name + ".", 8000);
                    else {
                        String err = null;
                        try { err = (wresp.errorBody() != null) ? wresp.errorBody().string() : null; } catch (Exception ignored) {}
                        Log.e(TAG, "WA send failed: HTTP=" + (wresp != null ? wresp.code() : -1)
                                + " status=" + (wbody != null ? wbody.status : "null")
                                + " id=" + (wbody != null ? wbody.id : "null")
                                + " err=" + err);
                        if (err != null && (err.contains("recipient_not_allowed") || err.contains("131030")))
                            sayViaWakeService("No pude enviar por WhatsApp porque ese número no está autorizado aún.", 12000);
                        else
                            sayViaWakeService("No pude mandar el mensaje ahora.", 8000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/whatsapp/send", ex);
                    sayViaWakeService("Tuve un problema mandando el mensaje.", 8000);
                }

                stopSelf(); return;
            }

            case "SPOTIFY_PLAY": {
                String query = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (query == null || query.isBlank()) {
                    sayViaWakeService("¿Qué querés escuchar en Spotify?", 7000);
                    stopSelf(); return;
                }

                try {
                    retrofit2.Response<SpotifyStatus> s = RetrofitClient.api().spotifyStatus().execute();
                    SpotifyStatus st = s.isSuccessful() ? s.body() : null;
                    if (st == null) { sayViaWakeService("No pude verificar Spotify ahora.", 6000); stopSelf(); return; }
                    if (!st.connected) {
                        String url = st.loginUrl;
                        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                                this, 1001, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                        NotificationService.simpleActionNotification(this, "Conectar Spotify",
                                "Tocá para vincular tu cuenta de Spotify.", pi);
                        sayViaWakeService("Necesito conectar tu Spotify. Tocá la notificación para autorizar.", 11000);
                        postWatchdog(8000); stopSelf(); return;
                    }
                    if (st.premium != null && !st.premium) {
                        sayViaWakeService("Tu cuenta de Spotify no es Premium.", 9000);
                        stopSelf(); return;
                    }

                    // SIN intentos extra: si no hay dispositivo, solo aviso/abro Spotify y corto.
                    if (st.deviceCount != null && st.deviceCount == 0) {
                        Intent open = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                        if (open != null) {
                            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                                    this, 1002, open, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                            NotificationService.simpleActionNotification(this, "Abrir Spotify",
                                    "Tocá para activar un dispositivo.", pi);
                        }
                        sayViaWakeService("No encuentro un dispositivo de Spotify. Abrí Spotify una vez y volvemos a intentar.", 11000);
                        stopSelf(); return;
                    }

                    java.util.Map<String,String> body = new java.util.HashMap<>();
                    body.put("query", query);
                    if (st.suggestedDeviceId != null) body.put("deviceId", st.suggestedDeviceId);

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
                        sayViaWakeService(speak, 9000);
                    }

                } catch (Exception ex) {
                    Log.e(TAG, "Spotify play error", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 7000);
                }

                stopSelf(); return;
            }

            case "SPOTIFY_PAUSE": {
                try {
                    retrofit2.Response<SpotifyResponse> r = RetrofitClient.api().spotifyPause().execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 9000);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude pausar Spotify.", 7000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/pause", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 8000);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_RESUME": {
                sayViaWakeService("No pude continuar la reproducción.", 7000);
                stopSelf(); return;
            }

            case "SPOTIFY_NEXT": {
                try {
                    retrofit2.Response<SpotifyResponse> r = RetrofitClient.api().spotifyNext().execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 9000);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude pasar al siguiente.", 7000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/next", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 8000);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_PREV": {
                try {
                    retrofit2.Response<SpotifyResponse> r = RetrofitClient.api().spotifyPrev().execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 9000);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude volver al anterior.", 7000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/prev", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 8000);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_SET_VOLUME": {
                String v = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (v == null || v.trim().isEmpty()) v = "up";
                try {
                    retrofit2.Response<SpotifyResponse> r =
                            RetrofitClient.api().spotifyVolume(new SpotifyVolumeRequest(v.trim())).execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 9000);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude ajustar el volumen en Spotify.", 8000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/volume", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 8000);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_SET_SHUFFLE": {
                String state = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (state == null || state.isBlank()) state = "on";
                try {
                    retrofit2.Response<SpotifyResponse> r =
                            RetrofitClient.api().spotifyShuffle(new SpotifyShuffleRequest(state)).execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 9000);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude cambiar el modo aleatorio.", 8000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/shuffle", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 8000);
                }
                stopSelf(); return;
            }

            case "SPOTIFY_SET_REPEAT": {
                String state = (nres != null && nres.slots != null) ? nres.slots.message_text : null;
                if (state == null || state.isBlank()) state = "track";
                try {
                    retrofit2.Response<SpotifyResponse> r =
                            RetrofitClient.api().spotifyRepeat(new SpotifyRepeatRequest(state)).execute();
                    if (r.code() == 401 || r.code() == 403) {
                        sayViaWakeService("Necesitás vincular tu cuenta de Spotify en la app.", 9000);
                    } else if (!isOk(r)) {
                        sayViaWakeService("No pude cambiar el modo de repetición.", 8000);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/spotify/repeat", ex);
                    sayViaWakeService("Tuve un problema con Spotify.", 8000);
                }
                stopSelf(); return;
            }

            case "CANCEL": {
                sayViaWakeService("Listo.", 6000);
                stopSelf(); return;
            }
            case "ANSWER":
            case "UNKNOWN":
            default: {
                try {
                    AskRequest rq = new AskRequest();
                    rq.prompt = transcript;
                    Response<AskResponse> r2 = RetrofitClient.api().ask(rq).execute();
                    String reply = (r2.isSuccessful() && r2.body() != null && r2.body().reply != null)
                            ? r2.body().reply.trim()
                            : "No estoy seguro, ¿podés repetir?";
                    sayViaWakeService(TtsSanitizer.sanitizeForTTS(reply), 12000);
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/ask", ex);
                    sayViaWakeService("Tuve un problema procesando eso.", 8000);
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
                .putExtra(WakeWordService.EXTRA_AFTER_SAY_USER_NAME, userName);
        if (nextFallMode != null) {
            say.putExtra(WakeWordService.EXTRA_AFTER_SAY_FALL_MODE, nextFallMode);
        }
        androidx.core.content.ContextCompat.startForegroundService(this, say);
    }

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
}
