package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.toto_app.actions.DeviceActions;
import com.example.toto_app.audio.InstructionCapture;
import com.example.toto_app.network.APIService;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.TranscriptionResponse;
import com.example.toto_app.nlp.InstructionRouter;
import com.example.toto_app.nlp.QuickTimeParser;

import java.io.File;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
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

    @Override
    public void onCreate() { super.onCreate(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("user_name")) {
            String incoming = intent.getStringExtra("user_name");
            if (incoming != null && !incoming.trim().isEmpty()) {
                userName = incoming.trim();
            }
        }
        Executors.newSingleThreadExecutor().execute(this::doWork);
        return START_NOT_STICKY;
    }

    private void doWork() {
        File cacheDir = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
        File wav = new File(cacheDir, "toto_instruction_" + SystemClock.elapsedRealtime() + ".wav");

        // 1) Captura hasta silencio
        InstructionCapture.Config cfg = new InstructionCapture.Config();
        cfg.sampleRate = 16000;
        cfg.maxDurationMs = 15000;
        cfg.trailingSilenceMs = 1800;
        cfg.silenceDbfs = -45.0;
        cfg.frameMs = 30;

        Log.d(TAG, "Grabando a WAV: " + wav.getAbsolutePath());
        InstructionCapture.captureToWav(wav, cfg, new InstructionCapture.Listener(){});

        String transcript = "";
        try {
            // 2) STT
            APIService api = RetrofitClient.api();
            RequestBody fileBody = RequestBody.create(wav, MediaType.parse("audio/wav"));
            MultipartBody.Part audioPart = MultipartBody.Part.createFormData("audio", wav.getName(), fileBody);
            Call<TranscriptionResponse> call = api.transcribe(audioPart, null, null);
            Response<TranscriptionResponse> resp = call.execute();

            if (resp.isSuccessful() && resp.body() != null) {
                transcript = resp.body().text != null ? resp.body().text.trim() : "";
                Log.d(TAG, "INSTRUCCIÓN (backend STT): " + transcript);
            } else {
                Log.e(TAG, "Transcribe error HTTP: " + (resp != null ? resp.code(): -1));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error llamando al backend STT", e);
        } finally {
            try { wav.delete(); } catch (Exception ignored) {}
        }

        if (transcript.isEmpty()) {
            sayViaWakeService("No te escuché bien, " + userName + ".", 6000);
            stopSelf();
            return;
        }

        // ==== Alarmas locales (RELATIVAS y ABSOLUTAS) ====
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
        // ==== Fin bloque de alarmas ====

        // 3) NLU remoto con fallback local
        String intentName = null;
        com.example.toto_app.network.NluRouteResponse nres = null;
        try {
            com.example.toto_app.network.NluRouteRequest nreq = new com.example.toto_app.network.NluRouteRequest();
            nreq.text = transcript;
            nreq.locale = "es-AR";
            nreq.context = null;
            nreq.hints = null;
            retrofit2.Response<com.example.toto_app.network.NluRouteResponse> rNlu =
                    RetrofitClient.api().nluRoute(nreq).execute();
            if (rNlu.isSuccessful()) nres = rNlu.body();
        } catch (Exception e) {
            Log.e(TAG, "Error llamando /api/nlu/route", e);
        }

        if (nres != null) {
            Log.d(TAG, "NLU/route → intent=" + safe(nres.intent)
                    + " conf=" + nres.confidence
                    + " needsConf=" + nres.needs_confirmation
                    + " slots=" + slotsToString(nres.slots)
                    + " ack=" + safe(nres.ack_tts));
        } else {
            Log.d(TAG, "NLU/route → nres=null");
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

        // Evitar “No estoy seguro…” si igual actuamos
        if (nres != null && nres.ack_tts != null && !nres.ack_tts.trim().isEmpty()) {
            boolean isAnswerish = "ANSWER".equals(intentName) || "UNKNOWN".equals(intentName);
            if (!"QUERY_TIME".equals(intentName) && !"QUERY_DATE".equals(intentName)
                    && !isAnswerish && !"CALL".equals(intentName)) {
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

            // ====== SOLO CAMBIOS EN LLAMADAS A PARTIR DE ACÁ ======
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

                boolean hasCall = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CALL_PHONE)
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

                // Resolver contacto o usar número si el usuario dijo dígitos
                DeviceActions.ResolvedContact rc = DeviceActions.resolveContactByNameFuzzy(this, who);
                if ((rc == null || rc.number == null || rc.number.isEmpty())
                        && who.startsWith("a") && who.length() >= 3) {
                    rc = DeviceActions.resolveContactByNameFuzzy(this, who.substring(1));
                }
                if ((rc == null || rc.number == null || rc.number.isEmpty()) && looksLikePhoneNumber(who)) {
                    String dial = normalizeDialable(who);
                    // score 1.0 para indicar match fuerte (número explícito)
                    rc = new DeviceActions.ResolvedContact(who, dial, 1.0);
                }

                if (rc == null || rc.number == null || rc.number.isEmpty()) {
                    // Sin número -> abrir marcador (no directo) como última opción
                    showCallNotification(who, null);
                    sayViaWakeService("No encontré a " + who + " en tus contactos. Te dejé una notificación para marcar.", 12000);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                if (!hasCall) {
                    // Pedir permiso CALL_PHONE para llamada directa
                    sayViaWakeService("Necesito permiso de llamadas para marcar directamente. Abrí la app para darlo.", 10000);
                    Intent perm = new Intent(this, com.example.toto_app.MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("request_call_phone_perm", true);
                    startActivity(perm);
                    postWatchdog(8000);
                    stopSelf();
                    return;
                }

                // === Directo: placeCall si somos dialer; si no, ACTION_CALL desde FGS ===
                boolean ok = placeDirectCall(rc.name, rc.number);
                if (ok) {
                    sayViaWakeService("Llamando a " + rc.name + ".", 4000);
                } else {
                    // fallback de verdad
                    showCallNotification(rc.name, rc.number);
                    sayViaWakeService("No pude iniciar la llamada directa. Te dejé una notificación para marcar.", 12000);
                }

                postWatchdog(8000);
                stopSelf();
                return;
            }
            // ====== FIN CAMBIOS EN LLAMADAS ======

            case "CANCEL": {
                sayViaWakeService("Listo.", 6000);
                stopSelf();
                return;
            }
            case "OPEN_APP":
            case "SEND_MESSAGE":
            case "ANSWER":
            case "UNKNOWN":
            default: {
                try {
                    com.example.toto_app.network.AskRequest rq = new com.example.toto_app.network.AskRequest();
                    rq.prompt = transcript;
                    retrofit2.Response<com.example.toto_app.network.AskResponse> r2 =
                            RetrofitClient.api().ask(rq).execute();
                    String reply = (r2.isSuccessful() && r2.body() != null && r2.body().reply != null)
                            ? r2.body().reply.trim()
                            : "No estoy seguro, ¿podés repetir?";
                    sayViaWakeService(sanitizeForTTS(reply), 12000);
                } catch (Exception ex) {
                    Log.e(TAG, "Error /api/ask", ex);
                    sayViaWakeService("Tuve un problema procesando eso.", 8000);
                }
                stopSelf();
                return;
            }
        }
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

    /** ¿Somos app de Teléfono por defecto? (requisito ideal para placeCall directo) */
    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
                return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER);
            } catch (Throwable ignored) {}
        }
        // Compat
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                String pkg = tm.getDefaultDialerPackage();
                return getPackageName().equals(pkg);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Intenta llamada directa: placeCall si somos dialer, si no ACTION_CALL desde FGS. */
    private boolean placeDirectCall(String displayName, String number) {
        boolean startedFg = startTempForeground("Llamando a " + (displayName == null ? "" : displayName));
        try {
            if (isDefaultDialer()) {
                try {
                    TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
                    if (tm == null) return false;
                    Uri uri = Uri.fromParts("tel", number, null);
                    Bundle extras = new Bundle();
                    tm.placeCall(uri, extras);
                    return true;
                } catch (Throwable t) {
                    Log.e(TAG, "placeCall falló, pruebo ACTION_CALL", t);
                    // Intento ACTION_CALL abajo
                }
            }
            // No somos dialer o placeCall falló → ACTION_CALL (requiere CALL_PHONE)
            try {
                Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
                call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(call);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "ACTION_CALL falló", t);
                return false;
            }
        } finally {
            stopTempForeground();
        }
    }

    /** Inicia FGS con tipo PHONE_CALL; devuelve true si quedó iniciado. */
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

    // ===== Notificación de llamada (fallback real) =====
    private void showCallNotification(String name, @Nullable String number) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CALL_CHANNEL_ID, "Confirmación de llamada", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Acción para abrir el marcador o llamar a un contacto");
            nm.createNotificationChannel(ch);
        }

        Intent intent = (number != null && !number.isEmpty())
                ? new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                : new Intent(Intent.ACTION_DIAL);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = (int) (System.currentTimeMillis() & 0xFFFFFF);
        PendingIntent pi = PendingIntent.getActivity(
                this, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Llamar a " + name)
                .setContentText(number != null ? number : "Abrir marcador")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(new NotificationCompat.Action(android.R.drawable.sym_action_call, "Llamar", pi));

        int notifyId = CALL_NOTIFY_ID_BASE + (name != null ? name.hashCode() & 0x0FFF : 0);
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

    /** Usa el TTS del WakeWordService (FGS) y deja un watchdog para rearmar wake. */
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

    /**
     * Intenta parsear ISO-8601 (con o sin zona) y devolver HH:mm locales.
     */
    @Nullable
    private static int[] tryParseIsoToLocalHourMinute(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        String[] patterns = new String[]{
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

    // Sanitizador de texto para TTS (elimina Markdown y símbolos)
    private static String sanitizeForTTS(String s) {
        if (s == null) return "";
        String out = s;
        out = out.replaceAll("(?s)```.*?```", " ");
        out = out.replace("`", "");
        out = out.replaceAll("!\\[(.*?)\\]\\((.*?)\\)", "$1");
        out = out.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "$1");
        out = out.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        out = out.replaceAll("(?m)^\\s*>\\s?", "");
        out = out.replace("*", "");
        out = out.replace("_", "");
        out = out.replaceAll("(?m)^\\s*([-*+]|•)\\s+", "");
        out = out.replaceAll("(?m)^\\s*(\\d+)[\\.)]\\s+", "$1. ");
        out = out.replaceAll("\\r?\\n\\s*\\r?\\n", ". ");
        out = out.replaceAll("\\r?\\n", ". ");
        out = out.replaceAll("\\s{2,}", " ").trim();
        return out;
    }

    /** ¿El usuario dijo un número de teléfono? (muy laxo) */
    private static boolean looksLikePhoneNumber(String s) {
        if (s == null) return false;
        String t = s.replaceAll("[^0-9+]", "");
        return t.length() >= 6; // mínimo razonable
    }

    /** Deja solo dígitos y + para discado */
    private static String normalizeDialable(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9+]", "");
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() { super.onDestroy(); }
}
