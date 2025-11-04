package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import com.example.toto_app.falls.FallSignals;
import com.example.toto_app.util.TtsSanitizer;
import com.example.toto_app.util.UserDataManager;

public class WakeWordService extends Service implements RecognitionListener {

    public static final String ACTION_CMD_FINISHED = "com.example.toto_app.ACTION_CMD_FINISHED";
    public static final String ACTION_SAY = "com.example.toto_app.ACTION_SAY";
    public static final String ACTION_WHATSAPP_HANDLED = "com.example.toto_app.ACTION_WHATSAPP_HANDLED";
    public static final String EXTRA_ENQUEUE_IF_BUSY = "enqueue_if_busy";

    public static final String ACTION_PAUSE_LISTEN = "com.example.toto_app.ACTION_PAUSE_LISTEN";
    public static final String ACTION_RESUME_LISTEN = "com.example.toto_app.ACTION_RESUME_LISTEN";

    public static final String ACTION_STOP_TTS = "com.example.toto_app.ACTION_STOP_TTS";

    public static final String EXTRA_AFTER_SAY_START_SERVICE = "after_say_start_service";
    public static final String EXTRA_AFTER_SAY_USER_NAME = "after_say_user_name";
    public static final String EXTRA_AFTER_SAY_FALL_MODE = "after_say_fall_mode";
    public static final String EXTRA_AFTER_SAY_CONFIRM_WHATSAPP = "after_say_confirm_whatsapp";

    public static final String EXTRA_REASON = "reason";
    public static final String REASON_FALL_CLEAR = "FALL_CLEAR";

    private volatile boolean pendingWhatsAppConfirm = false;
    @Nullable
    private volatile String pendingWhatsAppText = null;
    @Nullable
    private volatile Intent pendingAfterSayWhatsApp = null;

    @Nullable
    private Intent pendingAfterSay;

    private static final String TAG = "WakeWord";
    private static final String CHANNEL_ID = "toto_listening";

    private static final long MIN_COOLDOWN_MS = 1500;
    private static final long DEDUPE_WINDOW_MS = 2500;

    private long lastTriggerAt = 0L;
    private long lastDetectionAt = 0L;
    private String lastDetectionText = "";
    private volatile boolean triggered = false;

    private UserDataManager userDataManager;

    private Model model;
    private SpeechService speechService;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final Random rng = new Random();

    private volatile boolean isSpeaking = false;
    private final java.util.Queue<String> pendingSentQueue = new java.util.ArrayDeque<>();

    private PowerManager.WakeLock wakeLock;
    @Nullable
    private AudioFocusRequest audioFocusRequest;

    private volatile boolean listeningPaused = false;

    @Nullable
    private String currentUtteranceKind = null;

    private volatile long blockWakeUntilMs = 0L;

    private static final String[] ACK_TEMPLATES = new String[]{
            "¿En qué te puedo ayudar, %s?",
            "Sí, %s, decime.",
            "Te escucho, %s.",
            "%s, decime.",
            "¿Qué necesitás, %s?",
            "Acá estoy, %s."
    };

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":MicLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private final BroadcastReceiver cmdFinishedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "ACTION_CMD_FINISHED (broadcast) → rearmar wake");
            rearmWake();
        }
    };

    private final BroadcastReceiver pendingSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String txt = (intent != null) ? intent.getStringExtra("text") : null;
            if (txt == null || txt.trim().isEmpty()) return;
            Log.d(TAG, "pendingSentReceiver received text=" + txt);
            try {
                if (ttsReady && !isSpeaking && !FallSignals.isActive() && currentUtteranceKind == null) {
                    Log.d(TAG, "pendingSentReceiver: speaking immediately");
                    speakText(txt);
                } else {
                    Log.d(TAG, "pendingSentReceiver: enqueueing because busy/not ready");
                    synchronized (pendingSentQueue) {
                        pendingSentQueue.add(txt);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "pendingSentReceiver error", e);
            }
        }
    };

    private final BroadcastReceiver fallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String src = intent.getStringExtra(FallSignals.EXTRA_SOURCE);
            String un = intent.getStringExtra(FallSignals.EXTRA_USER_NAME);

            if (!FallSignals.tryActivate()) return;

            Intent stopTts = new Intent(WakeWordService.this, WakeWordService.class).setAction(ACTION_STOP_TTS);
            androidx.core.content.ContextCompat.startForegroundService(WakeWordService.this, stopTts);

            Intent pause = new Intent(WakeWordService.this, WakeWordService.class).setAction(ACTION_PAUSE_LISTEN);
            androidx.core.content.ContextCompat.startForegroundService(WakeWordService.this, pause);

            String who = (un == null || un.trim().isEmpty()) ? userDataManager.getUserName() : un.trim();
            Intent say = new Intent(WakeWordService.this, WakeWordService.class)
                    .setAction(ACTION_SAY)
                    .putExtra("text", "Escuché un golpe. ¿Estás bien?")
                    .putExtra(EXTRA_AFTER_SAY_START_SERVICE, true)
                    .putExtra(EXTRA_AFTER_SAY_USER_NAME, who)
                    .putExtra(EXTRA_AFTER_SAY_FALL_MODE, "AWAIT:0");
            androidx.core.content.ContextCompat.startForegroundService(WakeWordService.this, say);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        
        userDataManager = new UserDataManager(getApplicationContext());

        IntentFilter filter = new IntentFilter(ACTION_CMD_FINISHED);
        ContextCompat.registerReceiver(this, cmdFinishedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter fFall = new IntentFilter(FallSignals.ACTION_FALL_DETECTED);
        ContextCompat.registerReceiver(this, fallReceiver, fFall, ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter fPending = new IntentFilter("com.example.toto_app.ACTION_PENDING_SENT");
        ContextCompat.registerReceiver(this, pendingSentReceiver, fPending, ContextCompat.RECEIVER_NOT_EXPORTED);

        createChannel();
        updateForegroundNotification("Escuchando \"Toto\"");

        LibVosk.setLogLevel(LogLevel.WARNINGS);
        initTTS();

        StorageService.unpack(
                this,
                "model-es",
                "model",
                new StorageService.Callback<Model>() {
                    @Override
                    public void onComplete(Model m) {
                        model = m;
                        com.example.toto_app.services.WakeWordServiceModelHolder.setModel(m);
                        startWakeListening();
                    }
                },
                new StorageService.Callback<IOException>() {
                    @Override
                    public void onComplete(IOException e) {
                        Log.e(TAG, "Error cargando modelo Vosk", e);
                        Toast.makeText(WakeWordService.this, "Error cargando modelo Vosk", Toast.LENGTH_LONG).show();
                        stopSelf();
                    }
                }
        );
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent i = new Intent(getApplicationContext(), WakeWordService.class);
        androidx.core.content.ContextCompat.startForegroundService(getApplicationContext(), i);
        super.onTaskRemoved(rootIntent);
    }

    private void initTTS() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "AR"));
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);

                tts.setPitch(0.96f);
                tts.setSpeechRate(0.9f);

                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                tts.setAudioAttributes(attrs);

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.d(TAG, "TTS onStart utteranceId=" + utteranceId);
                        if (utteranceId != null && (utteranceId.startsWith("toto_ack_") || utteranceId.startsWith("toto_say_"))) {
                            isSpeaking = true;
                            blockWakeUntilMs = SystemClock.elapsedRealtime() + 3_000;
                            requestTtsAudioFocus();
                        }
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Log.d(TAG, "TTS onDone utteranceId=" + utteranceId);
                        new android.os.Handler(getMainLooper()).post(() -> {
                            finishAfterTts(currentUtteranceKind);
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        onDone(utteranceId);
                    }
                });
                new android.os.Handler(getMainLooper()).post(() -> {
                    try {
                        String pending = null;
                        synchronized (pendingSentQueue) {
                            pending = pendingSentQueue.poll();
                        }
                        if (pending != null && !isSpeaking && !FallSignals.isActive() && currentUtteranceKind == null) {
                            Log.d(TAG, "initTTS: speaking queued pending-sent TTS after TTS init: " + pending);
                            speakText(pending);
                        } else if (pending != null) {
                            synchronized (pendingSentQueue) {
                                pendingSentQueue.add(pending);
                            }
                            Log.d(TAG, "initTTS: queued pending-sent kept because busy");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "initTTS pending speak error", e);
                    }
                });
            } else {
                ttsReady = false;
                Log.w(TAG, "TTS no inicializó correctamente");
            }
        });
    }

    private void startWakeListening() {
        try {
            stopListening();
            if (model == null) return;

            if (listeningPaused) {
                Log.d(TAG, "startWakeListening: en pausa → no inicio reconocimiento");
                updateForegroundNotification("Pausa: no estoy escuchando");
                return;
            }

            Recognizer rec = new Recognizer(model, 16000.0f);
            rec.setGrammar("[\"toto\"]");
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);

            acquireWakeLock();
            Log.d(TAG, "Wake listening iniciado");
            updateForegroundNotification("Escuchando \"Toto\"");
            String next = null;
            synchronized (pendingSentQueue) {
                next = pendingSentQueue.poll();
            }
            if (next != null && ttsReady && !FallSignals.isActive() && currentUtteranceKind == null) {
                Log.d(TAG, "startWakeListening: speaking queued pending-sent TTS");
                speakText(next);
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "startWakeListening error", e);
            stopSelf();
        }
    }

    private void stopListening() {
        if (speechService != null) {
            try {
                speechService.stop();
            } catch (Exception ignored) {
            }
            try {
                speechService.shutdown();
            } catch (Exception ignored) {
            }
            speechService = null;
        }
        abandonAudioFocus();
        releaseWakeLock();
    }

    private void requestTtsAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(fc -> {
                    })
                    .build();
            am.requestAudioFocus(audioFocusRequest);
        } else {
            am.requestAudioFocus(fc -> {
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) am.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            am.abandonAudioFocus(null);
        }
    }

    private void startInstructionService() {
        stopListening();
        Intent i = new Intent(this, InstructionService.class);
        i.putExtra("user_name", userDataManager.getUserName());
        startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_PAUSE_LISTEN.equals(action)) {
                pauseListening();
                return START_STICKY;
            }
            if (ACTION_RESUME_LISTEN.equals(action)) {
                resumeListening();
                maybeSpeakPendingWhatsApp();
                return START_STICKY;
            }
            if (ACTION_STOP_TTS.equals(action)) {
                stopSpeaking();
                return START_STICKY;
            }

            if (ACTION_SAY.equals(action)) {
                String toSay = intent.getStringExtra("text");
                boolean chain = intent.getBooleanExtra(EXTRA_AFTER_SAY_START_SERVICE, false);
                boolean confirmWa = intent.getBooleanExtra(EXTRA_AFTER_SAY_CONFIRM_WHATSAPP, false)
                        || intent.getBooleanExtra("confirmWa", false);
                boolean enqueueIfBusy = intent.getBooleanExtra(EXTRA_ENQUEUE_IF_BUSY, false)
                        || intent.getBooleanExtra("enqueueIfBusy", false);

                Intent next = null;
                if (chain) {
                    next = new Intent(this, InstructionService.class);
                    String un = intent.getStringExtra(EXTRA_AFTER_SAY_USER_NAME);
                    if (un != null) next.putExtra("user_name", un);
                    String fm = intent.getStringExtra(EXTRA_AFTER_SAY_FALL_MODE);
                    if (fm != null) next.putExtra("fall_mode", fm);
                    if (confirmWa) next.putExtra("confirm_whatsapp", true);
                }

                if (toSay != null && !toSay.trim().isEmpty()) {
                    String payload = toSay.trim();
                    payload = sanitizeWakeWordInside(payload);

                    boolean busy = isSpeaking || FallSignals.isActive() || InstructionService.isConversationActive();
                    if ((enqueueIfBusy || confirmWa) && (busy || pendingWhatsAppConfirm)) {
                        Log.d(TAG, "ACTION_SAY: skipping immediate/duplicate WhatsApp confirm because " +
                                (pendingWhatsAppConfirm ? "pending" : "busy"));
                        pendingWhatsAppConfirm = true;
                        pendingWhatsAppText = payload;
                        if (confirmWa && next != null) {
                            pendingAfterSayWhatsApp = next;
                        }
                        return START_NOT_STICKY;
                    }

                    if (chain && next != null) {
                        pendingAfterSay = next;
                    } else if (confirmWa && next != null) {
                        pendingAfterSay = next;
                    }
                    speakText(payload);
                } else {
                    if (chain && next != null) {
                        pendingAfterSay = next;
                    }
                }
                return START_NOT_STICKY;
            }

            if ("com.example.toto_app.ACTION_WHATSAPP_HANDLED".equals(action)) {
                Log.d(TAG, "ACTION_WHATSAPP_HANDLED received -> clearing pendingWhatsAppConfirm flag");
                pendingWhatsAppConfirm = false;
                pendingWhatsAppText = null;
                pendingAfterSayWhatsApp = null;
                return START_STICKY;
            }

            if (ACTION_CMD_FINISHED.equals(action)) {
                Log.d(TAG, "ACTION_CMD_FINISHED (startService) → rearmar wake");
                maybeSpeakPendingWhatsApp();
                rearmWake();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    private void maybeSpeakPendingWhatsApp() {
        if (!pendingWhatsAppConfirm) return;
        if (isSpeaking) return;
        if (FallSignals.isActive()) return;
        if (InstructionService.isConversationActive()) return;
        String txt = pendingWhatsAppText;
        if (txt == null || txt.trim().isEmpty()) return;
        Log.d(TAG, "maybeSpeakPendingWhatsApp: speaking now pending confirm");
        pendingWhatsAppConfirm = false;
        pendingWhatsAppText = null;
        if (pendingAfterSayWhatsApp != null) {
            pendingAfterSay = pendingAfterSayWhatsApp;
            pendingAfterSayWhatsApp = null;
        }
        speakText(txt);
    }

    private void finishAfterTts(@Nullable String kindSnapshot) {
        currentUtteranceKind = null;
        isSpeaking = false;
        abandonAudioFocus();

        if ("ACK".equals(kindSnapshot)) {
            if (!FallSignals.isActive()) {
                startInstructionService();
            }
            return;
        }
        if (pendingAfterSay != null) {
            try {
                startService(pendingAfterSay);
            } catch (Exception ignored) {
            }
            pendingAfterSay = null;
            return;
        }
        maybeSpeakPendingWhatsApp();

        triggered = false;
        lastDetectionText = "";
        lastDetectionAt = 0L;
        if (!listeningPaused) {
            startWakeListening();
        } else {
            Log.d(TAG, "Fin TTS: en pausa → no rearmo");
            updateForegroundNotification("Pausa: no estoy escuchando");
        }
    }

    private String sanitizeWakeWordInside(String s) {
        try {
            return s.replaceAll("(?i)\\btoto\\b", "to to");
        } catch (Exception ignore) {
            return s;
        }
    }

    private void rearmWake() {
        triggered = false;
        lastDetectionText = "";
        lastDetectionAt = 0L;
        if (listeningPaused) {
            Log.d(TAG, "Wake en pausa → no rearmo escucha");
            updateForegroundNotification("Pausa: no estoy escuchando");
            return;
        }
        new android.os.Handler(getMainLooper()).post(this::startWakeListening);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(cmdFinishedReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(fallReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(pendingSentReceiver);
        } catch (Exception ignored) {
        }
        stopListening();
        if (model != null) {
            model.close();
            model = null;
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
            tts.shutdown();
            tts = null;
        }
        releaseWakeLock();
        abandonAudioFocus();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onPartialResult(String hypothesis) {
        checkForWakeWord(hypothesis);
    }

    @Override
    public void onResult(String hypothesis) {
        checkForWakeWord(hypothesis);
    }

    @Override
    public void onFinalResult(String hypothesis) { }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "ASR error", e);
    }

    @Override
    public void onTimeout() {
    }

    private static final Pattern WAKE_PATTERN = Pattern.compile("\\btoto\\b");

    private void checkForWakeWord(String json) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now < blockWakeUntilMs) {
                return;
            }

            if (isSpeaking) {
                return;
            }

            if (FallSignals.isActive()) {
                return;
            }

            JSONObject jo = new JSONObject(json);
            String recognized = jo.optString("text", "");
            if (recognized == null || recognized.trim().isEmpty()) {
                recognized = jo.optString("partial", "");
            }
            if (recognized == null) recognized = "";
            recognized = recognized.toLowerCase(Locale.ROOT).trim();
            if (recognized.isEmpty()) return;

            if (recognized.equals(lastDetectionText) && (now - lastDetectionAt) < DEDUPE_WINDOW_MS) {
                Log.d(TAG, "Detección duplicada ignorada: " + recognized);
                return;
            }

            if (WAKE_PATTERN.matcher(recognized).find()) {
                lastDetectionText = recognized;
                lastDetectionAt = now;

                boolean cooling = (now - lastTriggerAt) < MIN_COOLDOWN_MS;
                if (!triggered && !cooling) {
                    triggered = true;
                    lastTriggerAt = now;
                    onWakeWordDetected();
                } else {
                    Log.d(TAG, "Ignorado (cooldown o ya triggered)");
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void onWakeWordDetected() {
        Log.d(TAG, "WAKE WORD DETECTED: TOTO");
        Toast.makeText(this, "¡Hola! Te escucho…", Toast.LENGTH_SHORT).show();

        if (FallSignals.isActive()) {
            Log.d(TAG, "WakeWord: caída activa → ignorar ACK/conversación");
            return;
        }

        if (ttsReady && tts != null) {
            String template = ACK_TEMPLATES[rng.nextInt(ACK_TEMPLATES.length)];
            String text = String.format(Locale.getDefault(), template, userDataManager.getUserName());
            String s = TtsSanitizer.sanitizeForTTS(text);

            Bundle params = new Bundle();
            String utteranceId = "toto_ack_" + System.currentTimeMillis();
            stopListening();
            isSpeaking = true;
            currentUtteranceKind = "ACK";
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        } else {
            startInstructionService();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Toto Listening",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Servicio de escucha de la palabra Toto");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void updateForegroundNotification(String contentText) {
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Toto")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(1001, notif);
    }

    private void speakText(String text) {
        stopListening();
        if (ttsReady && tts != null) {
            String s = TtsSanitizer.sanitizeForTTS(text);
            Bundle params = new Bundle();
            String utteranceId = "toto_say_" + System.currentTimeMillis();
            isSpeaking = true;
            currentUtteranceKind = "SAY";
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        } else {
            Log.w(TAG, "TTS no listo para hablar");
        }
    }

    private void stopSpeaking() {
        if (tts == null || !isSpeaking) {
            Log.d(TAG, "stopSpeaking: no hay TTS en curso");
            return;
        }
        try {
            tts.stop();
        } catch (Exception ignored) {
        }
        finishAfterTts(currentUtteranceKind);
    }

    private void pauseListening() {
        listeningPaused = true;
        stopListening();
        updateForegroundNotification("Pausa: no estoy escuchando");
        Log.d(TAG, "Wake pausado por acción de usuario");
    }

    private void resumeListening() {
        listeningPaused = false;
        Log.d(TAG, "Wake reanudado por acción de usuario");
        startWakeListening();
    }
}
