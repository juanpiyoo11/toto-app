package com.example.toto_app.stt;

import android.util.Log;

import com.example.toto_app.network.APIService;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.TranscriptionResponse;

import java.io.File;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Response;

public final class SttClient {
    private SttClient(){}

    public static String transcribe(File wav) {
        String transcript = "";
        boolean up = true;
        try {
            try { up = com.example.toto_app.services.BackendHealthManager.get().isBackendUp(); }
            catch (Throwable ignore) { up = true; }
        } catch (Throwable ignored) { up = true; }

        if (up) {
            try {
                APIService api = RetrofitClient.api();
                RequestBody fileBody = RequestBody.create(wav, MediaType.parse("audio/wav"));
                MultipartBody.Part audioPart = MultipartBody.Part.createFormData("audio", wav.getName(), fileBody);
                Call<TranscriptionResponse> call = api.transcribe(audioPart, null, null);
                Response<TranscriptionResponse> resp = call.execute();

                if (resp.isSuccessful() && resp.body() != null) {
                    transcript = resp.body().text != null ? resp.body().text.trim() : "";
                    Log.d("SttClient", "INSTRUCCIÓN (backend STT): " + transcript);
                    return transcript == null ? "" : transcript;
                } else {
                    Log.w("SttClient", "Transcribe error HTTP: " + (resp != null ? resp.code(): -1));
                    try { com.example.toto_app.services.BackendHealthManager.get().markFailure(); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                Log.w("SttClient", "Error llamando backend STT", e);
                try { com.example.toto_app.services.BackendHealthManager.get().markFailure(); } catch (Exception ignore) {}
            }
        }

        try {
            org.vosk.Model model = com.example.toto_app.services.WakeWordServiceModelHolder.getModel();
            if (model != null) {
                org.vosk.Recognizer rec = new org.vosk.Recognizer(model, 16000.0f);
                java.io.FileInputStream fis = null;
                try {
                    fis = new java.io.FileInputStream(wav);
                    long skip = fis.skip(44);
                    int avail = (int) Math.max(0, wav.length() - 44);
                    byte[] raw = new byte[avail];
                    int offset = 0; int read;
                    while (offset < avail && (read = fis.read(raw, offset, avail - offset)) > 0) offset += read;
                    if (offset > 0) rec.acceptWaveForm(raw, offset);
                    String res = rec.getFinalResult();
                    if (res != null) {
                        try {
                            org.json.JSONObject jo = new org.json.JSONObject(res);
                            transcript = jo.optString("text", "");
                        } catch (Exception jex) {
                            transcript = res;
                        }
                        Log.d("SttClient", "INSTRUCCIÓN (vosk local): " + transcript);
                        return transcript == null ? "" : transcript;
                    }
                } finally { if (fis != null) try { fis.close(); } catch (Exception ignore) {} }
            }
        } catch (Exception e) {
            Log.e("SttClient", "Vosk local STT failed", e);
        }

        return transcript == null ? "" : transcript;
     }
 }
