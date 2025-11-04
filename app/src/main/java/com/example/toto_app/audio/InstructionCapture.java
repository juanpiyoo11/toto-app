package com.example.toto_app.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class InstructionCapture {

    private static final String TAG = "InstructionCapture";

    public static class Config {
        public int sampleRate = 16000;
        public int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        public int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        public int maxDurationMs = 15000;
        public int trailingSilenceMs = 1800;
        public double silenceDbfs = -45.0;
        public int frameMs = 30;
        public int minSpeechMs = 300;
    }

    public interface Listener {
        default void onLevel(double dbfs) {}
        default void onStarted() {}
        default void onFinished(File wavFile) {}
        default void onError(Exception e) {}
    }

    private InstructionCapture() {}

    public static File captureToWav(Context ctx, File outWav, Config cfg, Listener listener) {
        int perm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            SecurityException se = new SecurityException("RECORD_AUDIO not granted");
            if (listener != null) listener.onError(se);
            Log.e(TAG, "Permiso RECORD_AUDIO denegado", se);
            return outWav;
        }
        return captureToWav(outWav, cfg, listener);
    }

    public static File captureToWav(File outWav, Config cfg, Listener listener) {
        AudioRecord rec = null;
        RandomAccessFile raf = null;
        try {
            int bytesPerSample = 2;
            int frameBytes = (cfg.sampleRate * bytesPerSample * cfg.frameMs) / 1000;

            int minBuf = AudioRecord.getMinBufferSize(cfg.sampleRate, cfg.channelConfig, cfg.audioFormat);
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                throw new IllegalStateException("getMinBufferSize() inválido: " + minBuf);
            }
            int bufSize = Math.max(minBuf, frameBytes * 4);

            try {
                rec = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        cfg.sampleRate,
                        cfg.channelConfig,
                        cfg.audioFormat,
                        bufSize
                );
            } catch (SecurityException se) {
                if (listener != null) listener.onError(se);
                Log.e(TAG, "Sin permiso RECORD_AUDIO al crear AudioRecord", se);
                return outWav;
            }

            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord no inicializó (state=" + rec.getState() + ")");
            }

            raf = new RandomAccessFile(outWav, "rw");
            writeWavHeader(raf, cfg.sampleRate, (short) 1, (short) 16, 0);

            byte[] buffer = new byte[frameBytes];
            long start = SystemClock.elapsedRealtime();
            long lastVoiceAt = start;
            long firstVoiceAt = 0L;
            long totalWritten = 0;

            if (listener != null) listener.onStarted();

            try {
                rec.startRecording();
            } catch (SecurityException se) {
                if (listener != null) listener.onError(se);
                Log.e(TAG, "Sin permiso RECORD_AUDIO al startRecording()", se);
                return outWav;
            }

            while (true) {
                int read = rec.read(buffer, 0, buffer.length);
                if (read <= 0) continue;

                double dbfs = dbfsPcm16(buffer, read);
                if (listener != null) listener.onLevel(dbfs);

                long now = SystemClock.elapsedRealtime();
                boolean isVoice = dbfs > cfg.silenceDbfs;

                if (isVoice) {
                    if (firstVoiceAt == 0L) firstVoiceAt = now;
                    lastVoiceAt = now;
                    raf.write(buffer, 0, read);
                    totalWritten += read;
                } else {
                    if (firstVoiceAt != 0L && (now - lastVoiceAt) <= cfg.trailingSilenceMs) {
                        raf.write(buffer, 0, read);
                        totalWritten += read;
                    }
                }

                if (now - start >= cfg.maxDurationMs) break;
                if (firstVoiceAt != 0L && (now - lastVoiceAt) >= cfg.trailingSilenceMs) break;
            }

            updateWavSizes(raf, totalWritten);

            if (listener != null) listener.onFinished(outWav);
            return outWav;

        } catch (Exception e) {
            if (listener != null) listener.onError(e);
            Log.e(TAG, "captureToWav error", e);
            return outWav;
        } finally {
            try { if (rec != null) rec.stop(); } catch (Exception ignored) {}
            try { if (rec != null) rec.release(); } catch (Exception ignored) {}
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
        }
    }


    private static double dbfsPcm16(byte[] data, int len) {
        if (len < 2) return -120.0;
        ByteBuffer bb = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        long sum = 0;
        int n = 0;
        while (bb.remaining() >= 2) {
            short s = bb.getShort();
            sum += (long) s * (long) s;
            n++;
        }
        if (n == 0) return -120.0;
        double rms = Math.sqrt(sum / (double) n) / 32768.0;
        if (rms < 1e-9) return -120.0;
        return 20.0 * Math.log10(rms);
    }

    private static void writeWavHeader(RandomAccessFile raf,
                                       int sampleRate,
                                       short channels,
                                       short bitsPerSample,
                                       long dataLen) throws Exception {
        raf.setLength(0);
        raf.seek(0);
        raf.writeBytes("RIFF");
        raf.writeInt(Integer.reverseBytes((int) (36 + dataLen)));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        raf.writeInt(Integer.reverseBytes(16));
        raf.writeShort(Short.reverseBytes((short) 1));
        raf.writeShort(Short.reverseBytes(channels));
        raf.writeInt(Integer.reverseBytes(sampleRate));
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        raf.writeInt(Integer.reverseBytes(byteRate));
        short blockAlign = (short) (channels * bitsPerSample / 8);
        raf.writeShort(Short.reverseBytes(blockAlign));
        raf.writeShort(Short.reverseBytes(bitsPerSample));
        raf.writeBytes("data");
        raf.writeInt(Integer.reverseBytes((int) dataLen));
    }

    private static void updateWavSizes(RandomAccessFile raf, long dataLen) throws Exception {
        raf.seek(4);
        raf.writeInt(Integer.reverseBytes((int) (36 + dataLen)));
        raf.seek(40);
        raf.writeInt(Integer.reverseBytes((int) dataLen));
    }
}
