package com.example.toto_app.falls;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Arrays;

public class STFT {
    public enum WindowType { HANN, RECT }

    public static float[][] computeMagnitudeSpectrogram(
            float[] audio, int sampleRate, int frameSize, int hopSize, WindowType winType
    ) {
        if (Integer.bitCount(frameSize) != 1)
            throw new IllegalArgumentException("frameSize debe ser potencia de 2");

        float[] window = buildWindow(frameSize, winType);
        int nFrames = 1 + (Math.max(0, audio.length - frameSize)) / hopSize;
        int fftBins = frameSize / 2 + 1;

        float[][] spec = new float[nFrames][fftBins];
        float[] real = new float[frameSize];
        float[] imag = new float[frameSize];

        for (int f = 0; f < nFrames; f++) {
            int start = f * hopSize;
            Arrays.fill(real, 0f);
            Arrays.fill(imag, 0f);

            for (int i = 0; i < frameSize; i++) {
                float s = (start + i < audio.length) ? audio[start + i] : 0f;
                real[i] = s * window[i];
            }

            FFT.fft(real, imag);

            for (int k = 0; k < fftBins; k++) {
                float re = real[k], im = imag[k];
                spec[f][k] = (float) Math.sqrt(re * re + im * im) + 1e-8f;
            }
        }
        return spec;
    }

    public static float[][] toDecibel(float[][] mag, float floorDb) {
        int F = mag.length;
        int K = (F > 0) ? mag[0].length : 0;

        float[][] db = new float[F][K];
        float maxDb = -Float.MAX_VALUE;

        for (int f = 0; f < F; f++) {
            for (int k = 0; k < K; k++) {
                float v = mag[f][k];
                float valDb = 20f * (float) (Math.log10(v));
                if (valDb < floorDb) valDb = floorDb;
                db[f][k] = valDb;
                if (valDb > maxDb) maxDb = valDb;
            }
        }

        float range = Math.max(1e-6f, maxDb - floorDb);
        for (int f = 0; f < F; f++) {
            for (int k = 0; k < K; k++) {
                db[f][k] = (db[f][k] - floorDb) / range;
            }
        }
        return db;
    }

    public static Bitmap toBitmap(float[][] spec01) {
        if (spec01 == null || spec01.length == 0) return null;
        int width = spec01.length;
        int height = spec01[0].length;

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < width; x++) {
            float[] col = spec01[x];
            for (int y = 0; y < height; y++) {
                float v = col[height - 1 - y];
                v = v < 0f ? 0f : (v > 1f ? 1f : v);
                int g = (int) (v * 255f);
                bmp.setPixel(x, y, Color.rgb(g, g, g));
            }
        }
        return bmp;
    }

    private static float[] buildWindow(int n, WindowType type) {
        float[] w = new float[n];
        if (type == WindowType.HANN) {
            for (int i = 0; i < n; i++)
                w[i] = 0.5f * (1f - (float) Math.cos((2 * Math.PI * i) / (n - 1)));
        } else {
            Arrays.fill(w, 1f);
        }
        return w;
    }
}

