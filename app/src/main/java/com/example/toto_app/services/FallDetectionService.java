package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.toto_app.falls.FallSignals;
import com.example.toto_app.falls.STFT;

import org.tensorflow.lite.support.audio.TensorAudio;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier.AudioClassifierOptions;
import org.tensorflow.lite.task.audio.classifier.Classifications;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import ar.edu.uade.toto_app.R;

public class FallDetectionService extends Service {

    private static final String TAG = "FallDetectionService";
    private static final String CH_ID = "toto_yamnet";
    private static final int NOTIF_ID = 774200;

    // Acciones para pausar/reanudar
    public static final String ACTION_PAUSE_FALL  = "com.example.toto_app.action.PAUSE_FALL";
    public static final String ACTION_RESUME_FALL = "com.example.toto_app.action.RESUME_FALL";

    // Audio
    private static final int SR   = 16000;
    private static final int CH   = AudioFormat.CHANNEL_IN_MONO;
    private static final int FMT  = AudioFormat.ENCODING_PCM_16BIT;
    private static final int SEC  = 3;           // impacto + post-silencio
    private static final int HOP  = 160;         // 10 ms
    private static final int FFTN = 512;

    // YAMNet (labels de “impacto”)
    private static final Set<String> IMPACT_LABELS = new HashSet<>();
    static {
        String[] labs = new String[]{
                "thump","thud","bang","slam","impact","collision","knock","tap","slap",
                "wood","wood thud","door","door slam","drop","object impact","smash","crash",
                "bump","hit","boom","punch","object drop","body","floor","ground",
                "cap gun","gunshot","explosion","chop"
        };
        for (String s : labs) IMPACT_LABELS.add(s.toLowerCase());
    }

    // Umbrales base (nos quedamos con el perfil actual)
    private static final float YAMNET_IMPACT_THRESHOLD = 0.20f;
    private static final int   IMPACT_TOP_K            = 3;
    private static final float RMS_PEAK_THRESHOLD      = 0.15f;
    private static final float LOWFREQ_RATIO_THRESHOLD = 0.35f; // filtro de graves base
    private static final float POST_SILENCE_RMS        = 0.04f;
    private static final float POST_SILENCE_SECONDS    = 0.40f;

    // Anti-agudos (aplausos/knocks cercanos)
    private static final float HF1_MIN_HZ              = 2000f;
    private static final float HF1_MAX_HZ              = 6000f;
    private static final float HF1_RATIO_MAX           = 0.65f;
    private static final float CENTROID_MAX_HZ         = 4200f;
    private static final float MIN_WIDTH_MS            = 30f;

    // -------- Camino alternativo FAR-FIELD (selectivo) --------
    // Permite LF bajo si el evento es lo bastante fuerte y no es “muy agudo”.
    private static final float FF_RMS_MIN              = 0.27f;  // exige golpe fuerte
    private static final float FF_HF_RATIO_MAX         = 0.46f;  // agudos contenidos
    private static final float FF_CENTROID_MAX_HZ      = 4050f;  // espectro no tan agudo
    private static final float FF_WIDTH_MS_MIN         = 28f;    // transiente no “pinch”
    // ----------------------------------------------------------

    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused  = new AtomicBoolean(false);

    private AudioClassifier yamnet;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();

        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Toto")
                .setContentText("Escuchando entorno (detección por sonido)")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        try {
            AudioClassifierOptions opts = AudioClassifierOptions.builder()
                    .setScoreThreshold(0f)
                    .setMaxResults(10)
                    .build();
            yamnet = AudioClassifier.createFromFileAndOptions(getApplicationContext(), "yamnet.tflite", opts);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo cargar YAMNet", e);
            stopSelf();
            return;
        }

        running.set(true);
        worker = new Thread(this::loop, "toto-yamnet-loop");
        worker.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PAUSE_FALL:
                    paused.set(true);
                    Log.d(TAG, "Fall detection PAUSED");
                    break;
                case ACTION_RESUME_FALL:
                    paused.set(false);
                    Log.d(TAG, "Fall detection RESUMED");
                    break;
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running.set(false);
        try { if (worker != null) worker.join(500); } catch (Exception ignore) {}
        worker = null;
        super.onDestroy();
    }

    private void loop() {
        AudioRecord rec = null;
        short[] cap = null;
        TensorAudio tensor;

        try {
            tensor = yamnet.createInputTensorAudio();
        } catch (Exception e) {
            Log.e(TAG, "No se pudo crear TensorAudio", e);
            stopSelf();
            return;
        }

        final int frame = FFTN;
        final int hop   = HOP;
        int off = 0;

        while (running.get()) {
            if (paused.get()) {
                if (rec != null) {
                    try { rec.stop(); } catch (Exception ignore) {}
                    rec.release();
                    rec = null;
                    cap = null;
                    off = 0;
                }
                SystemClock.sleep(150);
                continue;
            }

            if (rec == null) {
                int minBuf = AudioRecord.getMinBufferSize(SR, CH, FMT);
                int readBuf = Math.max(minBuf, SR / 2);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, CH, FMT, readBuf);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord no inicializó; reintento en 500ms");
                    rec.release();
                    rec = null;
                    SystemClock.sleep(500);
                    continue;
                }
                try { rec.startRecording(); } catch (Exception e) {
                    Log.e(TAG, "No se pudo iniciar grabación; reintento", e);
                    rec.release();
                    rec = null;
                    SystemClock.sleep(500);
                    continue;
                }
                cap = new short[SR * SEC];
                off = 0;
            }

            int readBuf = Math.max(AudioRecord.getMinBufferSize(SR, CH, FMT), SR / 2);
            short[] tmp = new short[readBuf];
            int n = rec.read(tmp, 0, tmp.length);
            if (n <= 0) continue;

            int remaining = cap.length - off;
            int copy = Math.min(remaining, n);
            System.arraycopy(tmp, 0, cap, off, copy);
            off += copy;

            if (off >= cap.length) {
                float[] audio = new float[off];
                for (int i = 0; i < off; i++) audio[i] = cap[i] / 32768f;

                float[][] mag  = STFT.computeMagnitudeSpectrogram(audio, SR, frame, hop, STFT.WindowType.HANN);
                float[][] db01 = STFT.toDecibel(mag, -80f); // solo para logs

                // YAMNet
                List<Classifications> results = null;
                String topLabel = null; float topScore = -1f;
                try {
                    tensor.load(audio);
                    results = yamnet.classify(tensor);
                    if (results != null) {
                        for (Classifications cls : results) {
                            for (Category c : cls.getCategories()) {
                                if (c.getScore() > topScore) { topScore = c.getScore(); topLabel = c.getLabel(); }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "YAMNet classify error", e);
                }

                boolean hasImpact = hasImpactLabelTopK(results, YAMNET_IMPACT_THRESHOLD, IMPACT_TOP_K);

                RmsPeakInfo peak = computePeakAndWidth(audio, SR, hop);
                float rmsPeak   = peak.rmsPeak;
                int   peakF     = peak.peakFrame;
                float widthMs   = peak.widthMs50;

                // Rasgos espectrales (en magnitud)
                float lfRatio   = lowFreqRatioMAG(mag,   peakF, SR, frame, 500f);
                float hfRatio   = bandRatioMAG  (mag,    peakF, SR, frame, HF1_MIN_HZ, HF1_MAX_HZ);
                float centroid  = spectralCentroidMAG(mag, peakF, SR, frame);

                int postStart = Math.min(audio.length, peakF * hop + Math.round(0.2f * SR));
                boolean postSilent = isPostSilent(audio, SR, postStart, POST_SILENCE_RMS, POST_SILENCE_SECONDS);

                boolean strongPeak = rmsPeak >= RMS_PEAK_THRESHOLD;
                boolean calmAfter  = postSilent;
                boolean widthOk    = widthMs  >= MIN_WIDTH_MS;

                // Camino "bassy" (clásico)
                boolean bassy      = lfRatio  >= LOWFREQ_RATIO_THRESHOLD;
                boolean vetoAgudo  = (hfRatio >= HF1_RATIO_MAX) || (centroid >= CENTROID_MAX_HZ);
                boolean passBassy  = strongPeak && widthOk && calmAfter && bassy && !vetoAgudo;

                // Camino FAR-FIELD (selectivo): fuerte + no tan agudo
                boolean passFarField =
                        strongPeak &&
                                calmAfter &&
                                (widthMs >= FF_WIDTH_MS_MIN) &&
                                (rmsPeak >= FF_RMS_MIN) &&
                                (hfRatio <= FF_HF_RATIO_MAX) &&
                                (centroid <= FF_CENTROID_MAX_HZ);

                boolean isFall = hasImpact && (passBassy || passFarField);

                String path = passBassy ? "LF" : (passFarField ? "FF" : "--");

                Log.d(TAG,
                        "Top=" + topLabel + " (" + String.format("%.2f", topScore) + ")  " +
                                "Impact=" + hasImpact +
                                " RMS=" + String.format("%.2f", rmsPeak) +
                                " LF%=" + String.format("%.2f", lfRatio) +
                                " HF%=" + String.format("%.2f", hfRatio) +
                                " Ctr=" + String.format("%.0f", centroid) +
                                " W=" + String.format("%.0fms", widthMs) +
                                " Post=" + calmAfter +
                                " path=" + path +
                                " → FALL=" + isFall
                );

                if (isFall) {
                    if (!com.example.toto_app.falls.FallSignals.tryActivate()) {
                        // ignorado, ya hay una caída en curso o cooldown
                    } else {
                        android.content.Context ctx = this;
                        android.content.Intent stopTts = new android.content.Intent(ctx, com.example.toto_app.services.WakeWordService.class)
                                .setAction(com.example.toto_app.services.WakeWordService.ACTION_STOP_TTS);
                        androidx.core.content.ContextCompat.startForegroundService(ctx, stopTts);

                        android.content.Intent pause = new android.content.Intent(ctx, com.example.toto_app.services.WakeWordService.class)
                                .setAction(com.example.toto_app.services.WakeWordService.ACTION_PAUSE_LISTEN);
                        androidx.core.content.ContextCompat.startForegroundService(ctx, pause);

                        android.content.Intent say = new android.content.Intent(ctx, com.example.toto_app.services.WakeWordService.class)
                                .setAction(com.example.toto_app.services.WakeWordService.ACTION_SAY)
                                .putExtra("text", "Escuché un golpe. ¿Estás bien?")
                                .putExtra(com.example.toto_app.services.WakeWordService.EXTRA_AFTER_SAY_START_SERVICE, true)
                                .putExtra(com.example.toto_app.services.WakeWordService.EXTRA_AFTER_SAY_USER_NAME, "Juan")
                                .putExtra(com.example.toto_app.services.WakeWordService.EXTRA_AFTER_SAY_FALL_MODE, "AWAIT:0");
                        androidx.core.content.ContextCompat.startForegroundService(ctx, say);
                    }

                    SystemClock.sleep(1200); // cooldown local corto sólo para logs/flujo
                }

                // deslizamiento 50%
                int keep = cap.length / 2;
                System.arraycopy(cap, keep, cap, 0, cap.length - keep);
                off = cap.length - keep;
            }
        }

        try {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignore) {}
                rec.release();
            }
        } catch (Exception ignore) {}
    }

    // ===== Helpers =====

    private static boolean hasImpactLabelTopK(List<Classifications> results, float minScore, int topK) {
        if (results == null) return false;
        for (Classifications cls : results) {
            List<Category> cats = cls.getCategories();
            if (cats == null || cats.isEmpty()) continue;
            ArrayList<Category> sorted = new ArrayList<>(cats);
            sorted.sort((a,b) -> Float.compare(b.getScore(), a.getScore()));
            int limit = Math.min(topK, sorted.size());
            for (int i = 0; i < limit; i++) {
                Category c = sorted.get(i);
                String lbl = c.getLabel() == null ? "" : c.getLabel().toLowerCase();
                if (c.getScore() >= minScore) {
                    for (String k : IMPACT_LABELS) if (lbl.contains(k)) return true;
                }
            }
        }
        return false;
    }

    private static class RmsPeakInfo {
        final float rmsPeak;
        final int peakFrame;
        final float widthMs50;
        RmsPeakInfo(float rmsPeak, int peakFrame, float widthMs50) {
            this.rmsPeak = rmsPeak; this.peakFrame = peakFrame; this.widthMs50 = widthMs50;
        }
    }

    private static RmsPeakInfo computePeakAndWidth(float[] audio, int sampleRate, int hopSize) {
        int win = Math.max(1, hopSize);
        int frames = Math.max(1, 1 + (audio.length - win) / hopSize);
        float[] rms = new float[frames];

        float best = -1f; int bestIdx = 0;
        for (int f = 0; f < frames; f++) {
            int start = f * hopSize;
            float r = rms(audio, start, win);
            rms[f] = r;
            if (r > best) { best = r; bestIdx = f; }
        }
        float thr = 0.5f * best;
        int left = bestIdx, right = bestIdx;
        while (left  > 0         && rms[left-1]  >= thr) left--;
        while (right < frames-1  && rms[right+1] >= thr) right++;

        int widthFrames = (right - left + 1);
        float widthMs = widthFrames * (1000f * hopSize / sampleRate);

        return new RmsPeakInfo(best, bestIdx, widthMs);
    }

    private static float rms(float[] x, int start, int len) {
        int end = Math.min(x.length, start + len);
        double acc = 0; int n = 0;
        for (int i = start; i < end; i++) { acc += x[i] * x[i]; n++; }
        return n > 0 ? (float)Math.sqrt(acc / n) : 0f;
    }

    // ===== Band features sobre MAGNITUD =====

    private static float lowFreqRatioMAG(float[][] mag, int frameIdx, int sampleRate, int frameSize, float cutoffHz) {
        if (mag == null || mag.length == 0) return 0f;
        frameIdx = Math.max(0, Math.min(frameIdx, mag.length - 1));
        float[] col = mag[frameIdx];
        if (col == null || col.length == 0) return 0f;

        float binHz = sampleRate / 2f / (col.length - 1);
        int cutoffBin = Math.min(col.length - 1, Math.max(1, Math.round(cutoffHz / binHz)));

        double low = 0, tot = 0;
        for (int k = 0; k < col.length; k++) {
            double v = col[k];
            tot += v;
            if (k <= cutoffBin) low += v;
        }
        return (float)((tot > 1e-9) ? (low / tot) : 0f);
    }

    private static float bandRatioMAG(float[][] mag, int frameIdx, int sampleRate, int frameSize, float lowHz, float highHz) {
        if (mag == null || mag.length == 0) return 0f;
        frameIdx = Math.max(0, Math.min(frameIdx, mag.length - 1));
        float[] col = mag[frameIdx];
        if (col == null || col.length == 0) return 0f;

        float binHz = sampleRate / 2f / (col.length - 1);
        int lo = Math.max(0, Math.round(lowHz  / binHz));
        int hi = Math.min(col.length - 1, Math.round(highHz / binHz));

        double acc = 0, tot = 0;
        for (int k = 0; k < col.length; k++) {
            double v = col[k];
            tot += v;
            if (k >= lo && k <= hi) acc += v;
        }
        return (float)((tot > 1e-9) ? (acc / tot) : 0f);
    }

    private static float spectralCentroidMAG(float[][] mag, int frameIdx, int sampleRate, int frameSize) {
        if (mag == null || mag.length == 0) return 0f;
        frameIdx = Math.max(0, Math.min(frameIdx, mag.length - 1));
        float[] col = mag[frameIdx];
        if (col == null || col.length == 0) return 0f;

        float binHz = sampleRate / 2f / (col.length - 1);
        double num = 0, den = 0;
        for (int k = 0; k < col.length; k++) {
            double f = k * binHz;
            double v = col[k];
            num += f * v;
            den += v;
        }
        return (float)((den > 1e-12) ? (num / den) : 0f);
    }

    private static boolean isPostSilent(float[] audio, int sampleRate, int fromSample, float rmsThresh, float seconds) {
        int need = Math.round(seconds * sampleRate);
        int end = Math.min(audio.length, fromSample + need);
        if (end <= fromSample) return false;
        float r = rms(audio, fromSample, end - fromSample);
        return r <= rmsThresh;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ContextCompat.getSystemService(this, NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = nm.getNotificationChannel(CH_ID);
        if (ch == null) {
            ch = new NotificationChannel(CH_ID, "Toto: audio ambiente", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Servicio de escucha ambiental para detección por sonido");
            nm.createNotificationChannel(ch);
        }
    }
}
