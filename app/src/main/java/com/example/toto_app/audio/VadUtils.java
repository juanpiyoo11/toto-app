package com.example.toto_app.audio;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

public final class VadUtils {
    private VadUtils(){}

    private static final String TAG = "VadUtils";

    public static boolean hasEnoughVoice(File wavFile, double cfgGateDbfs, int minVoicedMs) {
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

    private static class WavInfo {
        int sampleRate;
        int channels;
        int bitsPerSample;
        long dataOffset;
        long dataSize;
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
        switch (bytesPerSample) {
            case 1:
                int u = buf[index] & 0xFF;
                return ((u - 128) / 128.0);
            case 2:
                int lo = buf[index] & 0xFF;
                int hi = buf[index + 1];
                short s = (short)((hi << 8) | lo);
                return s / 32768.0;
            case 3: {
                int b0 = buf[index] & 0xFF;
                int b1 = buf[index + 1] & 0xFF;
                int b2 = buf[index + 2];
                int v = (b2 << 16) | (b1 << 8) | b0;
                if ((v & 0x00800000) != 0) v |= 0xFF000000;
                return v / 8388608.0;
            }
            case 4: {
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
}
