package com.example.toto_app.audio;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.*;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LocalStt {
    private static final String TAG = "LocalStt";
    private static final String ZIP_ASSET = "vosk-model-small-es-0.42.zip";
    private static final String MODEL_DIR  = "vosk-model-small-es-0.42";

    private static Model model;

    // Gramática acotada para CHECK/AWAIT (no cambia tu lógica; sólo mejora el reconocimiento).
    public static final String[] AWAIT_GRAMMAR = new String[]{
            "si","sí","estoy bien","no estoy bien","no esta bien","esta bien","todo bien","todo ok",
            "tranquilo","tranquila","ya estoy bien","no fue nada","no te preocupes","no hay problema",
            "no estoy mal","no me paso nada","no me pasó nada",
            "me caí","me cai","me lastime","me lastimé","no me puedo mover","no puedo levantarme","no puedo pararme","estoy mal",
            "ayuda","ayudame","ayúdame","auxilio","emergencia","ambulancia","doctor","medico","médico", "me he caído"
    };

    public static synchronized void init(Context ctx) throws IOException {
        if (model != null) return;

        File targetDir = new File(ctx.getNoBackupFilesDir(), MODEL_DIR);
        // Cargado perezoso: si no existe, lo extraemos desde assets (zip)
        if (!new File(targetDir, "am/final.mdl").exists()) {
            File zipOut = new File(ctx.getCacheDir(), "vosk_es.zip");
            copyAsset(ctx, ZIP_ASSET, zipOut);
            unzip(zipOut, ctx.getNoBackupFilesDir());
        }
        model = new Model(targetDir.getAbsolutePath());
        Log.d(TAG, "Vosk model loaded at: " + targetDir.getAbsolutePath());
    }

    /** Transcribe un WAV PCM 16 kHz (el que ya grabás). Grammar opcional (json array). */
    public static String transcribeFile(File wav, String[] grammar) throws IOException {
        if (model == null) throw new IllegalStateException("LocalStt.init() not called");

        WavInfo wi = WavInfo.parse(wav);
        if (wi == null || wi.sampleRate <= 0) throw new IOException("Invalid WAV");

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(wav))) {
            // Saltar cabecera hasta dataOffset
            long toSkip = wi.dataOffset;
            while (toSkip > 0) {
                long s = in.skip(toSkip);
                if (s <= 0) break;
                toSkip -= s;
            }

            Recognizer rec = (grammar == null || grammar.length == 0)
                    ? new Recognizer(model, wi.sampleRate)
                    : new Recognizer(model, wi.sampleRate, new JSONArray(Arrays.asList(grammar)).toString());

            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                rec.acceptWaveForm(buf, n);
            }
            String json = rec.getFinalResult();
            try {
                JSONObject o = new JSONObject(json);
                return o.optString("text", "").trim();
            } catch (Exception ignore) {
                return "";
            }
        }
    }

    private static void copyAsset(android.content.Context ctx, String assetName, File out) throws IOException {
        if (out.exists() && out.length() > 0) return;
        try (InputStream is = ctx.getAssets().open(assetName);
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] b = new byte[8192];
            int r;
            while ((r = is.read(b)) != -1) os.write(b, 0, r);
        }
    }

    private static void unzip(File zip, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                if (e.isDirectory()) {
                    if (!out.exists()) out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (!parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
        }
    }

    /** Parser mínimo de WAV para hallar dataOffset y sampleRate. */
    static final class WavInfo {
        int sampleRate, channels, bitsPerSample;
        long dataOffset, dataSize;

        static WavInfo parse(File f) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                byte[] hdr = new byte[12];
                if (raf.read(hdr) != 12) return null;
                if (!(hdr[0]=='R'&&hdr[1]=='I'&&hdr[2]=='F'&&hdr[3]=='F'&&hdr[8]=='W'&&hdr[9]=='A'&&hdr[10]=='V'&&hdr[11]=='E')) return null;

                WavInfo wi = new WavInfo();
                boolean haveFmt=false, haveData=false;

                while (raf.getFilePointer() < raf.length()) {
                    byte[] ch = new byte[8];
                    if (raf.read(ch) < 8) break;
                    String id = new String(ch,0,4, java.nio.charset.StandardCharsets.US_ASCII);
                    int size = (ch[4]&0xFF)|((ch[5]&0xFF)<<8)|((ch[6]&0xFF)<<16)|((ch[7]&0xFF)<<24);

                    if ("fmt ".equals(id)) {
                        byte[] fmt = new byte[size];
                        if (raf.read(fmt) != size) return null;
                        int audioFormat   = (fmt[0]&0xFF) | ((fmt[1]&0xFF)<<8);
                        wi.channels       = (fmt[2]&0xFF) | ((fmt[3]&0xFF)<<8);
                        wi.sampleRate     = (fmt[4]&0xFF)|((fmt[5]&0xFF)<<8)|((fmt[6]&0xFF)<<16)|((fmt[7]&0xFF)<<24);
                        wi.bitsPerSample  = (fmt[14]&0xFF)|((fmt[15]&0xFF)<<8);
                        haveFmt = (audioFormat == 1); // PCM
                    } else if ("data".equals(id)) {
                        wi.dataOffset = raf.getFilePointer();
                        wi.dataSize   = size;
                        haveData = true;
                        break;
                    } else {
                        raf.seek(raf.getFilePointer() + size);
                    }
                }
                return (haveFmt && haveData) ? wi : null;
            }
        }
    }
}
