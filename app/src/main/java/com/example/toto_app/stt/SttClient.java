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
        try {
            APIService api = RetrofitClient.api();
            RequestBody fileBody = RequestBody.create(wav, MediaType.parse("audio/wav"));
            MultipartBody.Part audioPart = MultipartBody.Part.createFormData("audio", wav.getName(), fileBody);
            Call<TranscriptionResponse> call = api.transcribe(audioPart, null, null);
            Response<TranscriptionResponse> resp = call.execute();

            if (resp.isSuccessful() && resp.body() != null) {
                transcript = resp.body().text != null ? resp.body().text.trim() : "";
                Log.d("SttClient", "INSTRUCCIÓN (backend STT): " + transcript);
            } else {
                Log.e("SttClient", "Transcribe error HTTP: " + (resp != null ? resp.code(): -1));
            }
        } catch (Exception e) {
            Log.e("SttClient", "Error llamando backend STT", e);
        }
        return transcript;
    }
}
