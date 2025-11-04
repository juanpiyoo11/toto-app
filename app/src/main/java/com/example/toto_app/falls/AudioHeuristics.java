package com.example.toto_app.falls;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.tensorflow.lite.support.label.Category;

public class AudioHeuristics {

    private static final Set<String> IMPACT_LABELS = new HashSet<>();
    static {
        String[] labs = new String[]{
                "thump", "thud", "bang", "slam", "impact", "collision", "knock",
                "tap", "slap", "wood", "door", "door slam", "object impact",
                "drop", "smash", "crash"
        };
        for (String s : labs) IMPACT_LABELS.add(s.toLowerCase());
    }

    public static boolean hasImpactLabel(List<List<Category>> groups, float minScore) {
        for (List<Category> cats : groups) {
            for (Category c : cats) {
                String lbl = c.getLabel().toLowerCase();
                if (c.getScore() >= minScore) {
                    for (String k : IMPACT_LABELS) {
                        if (lbl.contains(k)) return true;
                    }
                }
            }
        }
        return false;
    }

    public static float rms(float[] x, int start, int len) {
        int end = Math.min(x.length, start + len);
        double acc = 0;
        int n = 0;
        for (int i = start; i < end; i++) { acc += x[i] * x[i]; n++; }
        return n > 0 ? (float)Math.sqrt(acc / n) : 0f;
    }

    public static int findPeakFrame(float[] audio, int sampleRate, int hopSize) {
        int win = Math.max(1, hopSize);
        float best = -1f; int bestIdx = 0;
        int frames = 1 + (audio.length - win) / hopSize;
        for (int f = 0; f < frames; f++) {
            int start = f * hopSize;
            float r = rms(audio, start, win);
            if (r > best) { best = r; bestIdx = f; }
        }
        return bestIdx;
    }

    public static float lowFreqRatio(float[][] spec01, int frameIdx, int sampleRate, int frameSize, float cutoffHz) {
        if (spec01 == null || spec01.length == 0) return 0f;
        frameIdx = Math.max(0, Math.min(frameIdx, spec01.length - 1));
        float[] col = spec01[frameIdx];
        int bins = col.length;
        float binHz = sampleRate / 2f / (bins - 1);
        int cutoffBin = Math.min(bins - 1, Math.max(1, Math.round(cutoffHz / binHz)));

        double low = 0, tot = 0;
        for (int k = 0; k < bins; k++) {
            float v = col[k];
            tot += v;
            if (k <= cutoffBin) low += v;
        }
        return (float)((tot > 1e-9) ? (low / tot) : 0f);
    }

    public static boolean isPostSilent(float[] audio, int sampleRate, int fromSample, float rmsThresh, float seconds) {
        int need = Math.round(seconds * sampleRate);
        int end = Math.min(audio.length, fromSample + need);
        if (end <= fromSample) return false;
        float r = rms(audio, fromSample, end - fromSample);
        return r <= rmsThresh;
    }
}
