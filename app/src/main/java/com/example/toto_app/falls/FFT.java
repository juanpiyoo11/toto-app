package com.example.toto_app.falls;

public class FFT {
    public static void fft(float[] real, float[] imag) {
        int n = real.length;
        if (Integer.bitCount(n) != 1)
            throw new IllegalArgumentException("La longitud debe ser potencia de 2");

        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;
            for (; j >= bit; bit >>>= 1) j -= bit;
            j += bit;
            if (i < j) {
                float tr = real[i]; real[i] = real[j]; real[j] = tr;
                float ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wlen_r = (float) Math.cos(ang);
            float wlen_i = (float) Math.sin(ang);

            for (int i = 0; i < n; i += len) {
                float wr = 1f, wi = 0f;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;

                    float ur = real[u], ui = imag[u];
                    float vr = real[v] * wr - imag[v] * wi;
                    float vi = real[v] * wi + imag[v] * wr;

                    real[u] = ur + vr; imag[u] = ui + vi;
                    real[v] = ur - vr; imag[v] = ui - vi;

                    float nwr = wr * wlen_r - wi * wlen_i;
                    float nwi = wr * wlen_i + wi * wlen_r;
                    wr = nwr; wi = nwi;
                }
            }
        }
    }
}
