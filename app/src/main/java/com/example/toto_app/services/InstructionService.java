package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.toto_app.audio.InstructionCapture;
import com.example.toto_app.network.APIService;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.TranscriptionResponse;
import com.example.toto_app.actions.DeviceActions;
import com.example.toto_app.nlp.InstructionRouter;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Response;

public class InstructionService extends Service {

    private static final String TAG = "InstructionService";
    private static final String CHANNEL_ID = "toto_command";

    private String userName = "Juan";

    // TTS para confirmar acciones locales
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Toto capturando instrucción")
                .setContentText("Hablá y hacé pausas naturales.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(2001, n);

        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "AR"));
                tts.setPitch(1.0f);
                tts.setSpeechRate(1.0f);
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });
        if (tts != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { notifyFinishedAndStop(); }
                @Override public void onError(String utteranceId) { notifyFinishedAndStop(); }
            });
        }
    }

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

        // 1) Grabar hasta silencio
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
            // 2) Subir al backend (STT)
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
            speakOrFinish("No te escuché bien, " + userName + ".");
            return;
        }

        // 3) Router → acciones locales
        InstructionRouter.Result r = InstructionRouter.route(transcript);

        switch (r.action) {
            case QUERY_TIME: {
                Calendar c = Calendar.getInstance();
                int h = c.get(Calendar.HOUR_OF_DAY);
                int m = c.get(Calendar.MINUTE);
                speakOrFinish("Son las " + DeviceActions.hhmm(h, m) + ".");
                return;
            }
            case QUERY_DATE: {
                Calendar c = Calendar.getInstance();
                String msg = String.format(Locale.getDefault(),
                        "Hoy es %1$tA %1$te de %1$tB de %1$tY.", c);
                speakOrFinish(msg);
                return;
            }
            case SET_ALARM: {
                String when = DeviceActions.hhmm(r.hour, r.minute);
                speakThen("Listo, te pongo una alarma para las " + when + ".", new Runnable() {
                    @Override public void run() {
                        DeviceActions.setAlarm(InstructionService.this, r.hour, r.minute, "Toto");
                    }
                });
                return;
            }
            case CALL: {
                String who = (r.contactName != null) ? r.contactName : "desconocido";
                boolean found = DeviceActions.dialContactByName(this, who);
                if (found) {
                    speakOrFinish("Abriendo llamada a " + who + ".");
                } else {
                    speakOrFinish("No encontré a " + who + " en tus contactos.");
                }
                return;
            }
            case NONE:
            default: {
                try {
                    com.example.toto_app.network.AskRequest req = new com.example.toto_app.network.AskRequest();
                    req.prompt = transcript;
                    Response<com.example.toto_app.network.AskResponse> r2 =
                            RetrofitClient.api().ask(req).execute();
                    if (r2.isSuccessful() && r2.body() != null) {
                        String reply = (r2.body().reply != null) ? r2.body().reply.trim() : "";
                        Log.d(TAG, "LLM REPLY: " + reply);
                        speakOrFinish(reply);
                    } else {
                        Log.e(TAG, "ASK error HTTP: " + (r2 != null ? r2.code() : -1));
                        speakOrFinish("Tuve un problema procesando eso.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error llamando /api/ask", e);
                    speakOrFinish("Tuve un problema procesando eso.");
                }
                return;
            }
        }
    }

    private void speakThen(String text, Runnable after) {
        if (ttsReady && tts != null) {
            // Ejecutar acción después de hablar; notifyFinished se llama desde onDone()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "toto_local_action");
            // Programar la acción un pelín después (evita cortar el TTS en algunos OEM)
            new android.os.Handler(getMainLooper()).postDelayed(after, 350);
        } else {
            if (after != null) after.run();
            notifyFinishedAndStop();
        }
    }

    private void speakOrFinish(String text) {
        if (ttsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "toto_say_and_finish");
        } else {
            notifyFinishedAndStop();
        }
    }

    private void notifyFinishedAndStop() {
        // Avisar al WakeWordService que ya puede volver a modo wake
        sendBroadcast(new Intent(WakeWordService.ACTION_CMD_FINISHED));
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Toto Command", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Captura de instrucciones de voz");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        super.onDestroy();
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} tts.shutdown(); }
    }
}
