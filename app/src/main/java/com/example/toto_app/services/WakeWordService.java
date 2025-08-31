package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

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

public class WakeWordService extends Service implements RecognitionListener {

    public static final String ACTION_CMD_FINISHED = "com.example.toto_app.ACTION_CMD_FINISHED";
    public static final String ACTION_SAY = "com.example.toto_app.ACTION_SAY";
    private volatile boolean isSpeaking = false;
    private volatile boolean pendingWake = false;


    private static final String TAG = "WakeWord";
    private static final String CHANNEL_ID = "toto_listening";

    private static final long MIN_COOLDOWN_MS = 1500;
    private static final long DEDUPE_WINDOW_MS = 2500;

    private long lastTriggerAt = 0L;
    private long lastDetectionAt = 0L;
    private String lastDetectionText = "";
    private volatile boolean triggered = false;

    private String userName = "Juan";

    private Model model;
    private SpeechService speechService;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final Random rng = new Random();

    private static final String[] ACK_TEMPLATES = new String[]{
            "¿En qué te puedo ayudar, %s?",
            "Sí, %s, decime.",
            "Te escucho, %s.",
            "%s, decime.",
            "¿Qué necesitás, %s?",
            "Acá estoy, %s."
    };

    private final BroadcastReceiver cmdFinishedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "InstructionService finalizado");
            triggered = false;
            lastDetectionText = "";
            lastDetectionAt = 0L;

            if (isSpeaking) {
                // Estoy leyendo respuesta → diferir el wake
                pendingWake = true;
                Log.d(TAG, "Difiero wake (isSpeaking=true)");
            } else {
                startWakeListening();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter filter = new IntentFilter(ACTION_CMD_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cmdFinishedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cmdFinishedReceiver, filter);
        }

        createChannel();

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Toto escuchando")
                .setContentText("Decí \"Toto\" para activar")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(1001, notif);

        LibVosk.setLogLevel(LogLevel.WARNINGS);
        initTTS();

        StorageService.unpack(
                this,
                "model-es",
                "model",
                new StorageService.Callback<Model>() {
                    @Override public void onComplete(Model m) {
                        model = m;
                        startWakeListening();
                    }
                },
                new StorageService.Callback<IOException>() {
                    @Override public void onComplete(IOException e) {
                        Log.e(TAG, "Error cargando modelo Vosk", e);
                        Toast.makeText(WakeWordService.this, "Error cargando modelo Vosk", Toast.LENGTH_LONG).show();
                        stopSelf();
                    }
                }
        );
    }

    private void initTTS() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "AR"));
                tts.setPitch(1.0f);
                tts.setSpeechRate(1.0f);
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        if (utteranceId != null && (utteranceId.startsWith("toto_ack_") || utteranceId.startsWith("toto_say_"))) {
                            isSpeaking = true;
                        }
                    }
                    @Override public void onDone(String utteranceId) {
                        // Volvemos al hilo principal para tocar audio/ASR
                        new android.os.Handler(getMainLooper()).post(() -> {
                            if (utteranceId != null && utteranceId.startsWith("toto_ack_")) {
                                isSpeaking = false;
                                // Fin del ACK → iniciar captura
                                startInstructionService();
                            } else if (utteranceId != null && utteranceId.startsWith("toto_say_")) {
                                isSpeaking = false;
                                // Fin de la respuesta hablada → rearmar wake
                                triggered = false;
                                lastDetectionText = "";
                                lastDetectionAt = 0L;
                                pendingWake = false;
                                startWakeListening();
                            }
                        });
                    }
                    @Override public void onError(String utteranceId) {
                        onDone(utteranceId); // misma política que onDone
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
            Recognizer rec = new Recognizer(model, 16000.0f);
            rec.setGrammar("[\"toto\"]"); // solo wake
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);
            requestAudioFocus();
        } catch (Exception e) {
            Log.e(TAG, "startWakeListening error", e);
            stopSelf();
        }
    }

    private void stopListening() {
        if (speechService != null) {
            try { speechService.stop(); } catch (Exception ignored) {}
            try { speechService.shutdown(); } catch (Exception ignored) {}
            speechService = null;
        }
    }

    private void requestAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.requestAudioFocus(
                    fc -> { /* no-op */ },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
        }
    }

    private void startInstructionService() {
        // Cortar mic de wake antes de lanzar el de instrucción
        stopListening();

        Intent i = new Intent(this, InstructionService.class);
        i.putExtra("user_name", userName);
        androidx.core.content.ContextCompat.startForegroundService(this, i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_SAY.equals(intent.getAction())) {
                String toSay = intent.getStringExtra("text");
                if (toSay != null && !toSay.trim().isEmpty()) {
                    speakText(toSay.trim()); // NO iniciar InstructionService aquí
                }
                return START_NOT_STICKY;
            }
            if (intent.hasExtra("user_name")) {
                String incoming = intent.getStringExtra("user_name");
                if (incoming != null && !incoming.trim().isEmpty()) {
                    userName = incoming.trim();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(cmdFinishedReceiver); } catch (Exception ignored) {}
        stopListening();
        if (model != null) { model.close(); model = null; }
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} tts.shutdown(); tts = null; }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // === RecognitionListener ===
    @Override public void onPartialResult(String hypothesis) { /* ignorado */ }
    @Override public void onResult(String hypothesis) { checkForWakeWord(hypothesis); }
    @Override public void onFinalResult(String hypothesis) { }
    @Override public void onError(Exception e) { Log.e(TAG, "ASR error", e); }
    @Override public void onTimeout() { }

    private void checkForWakeWord(String json) {
        try {
            JSONObject jo = new JSONObject(json);
            String text = jo.optString("text", "").toLowerCase().trim();
            if (text.isEmpty()) return;

            long now = SystemClock.elapsedRealtime();
            if (text.equals(lastDetectionText) && (now - lastDetectionAt) < DEDUPE_WINDOW_MS) {
                Log.d(TAG, "Detección duplicada ignorada: " + text);
                return;
            }
            lastDetectionText = text;
            lastDetectionAt = now;

            if (text.contains("toto")) {
                boolean cooling = (now - lastTriggerAt) < MIN_COOLDOWN_MS;
                if (!triggered && !cooling) {
                    triggered = true;
                    lastTriggerAt = now;
                    onWakeWordDetected();
                } else {
                    Log.d(TAG, "Ignorado (cooldown o ya triggered)");
                }
            }
        } catch (Exception ignored) { }
    }

    private void onWakeWordDetected() {
        Log.d(TAG, "WAKE WORD DETECTED: TOTO");
        Toast.makeText(this, "¡Hola! Te escucho…", Toast.LENGTH_SHORT).show();

        if (ttsReady && tts != null) {
            String template = ACK_TEMPLATES[rng.nextInt(ACK_TEMPLATES.length)];
            String text = String.format(Locale.getDefault(), template, userName);
            Bundle params = new Bundle();
            String utteranceId = "toto_ack_" + System.currentTimeMillis();
            // Cortar mic para que el TTS no se auto-dispare
            stopListening();
            isSpeaking = true;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        } else {
            startInstructionService();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Toto Listening", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Servicio de escucha de la palabra Toto");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void speakText(String text) {
        // Apagar wake ASR para no auto-disparar
        stopListening();
        if (ttsReady && tts != null) {
            Bundle params = new Bundle();
            String utteranceId = "toto_say_" + System.currentTimeMillis();
            isSpeaking = true;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        } else {
            Log.w(TAG, "TTS no listo para hablar");
        }
    }

}
