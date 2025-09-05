package com.example.toto_app.network;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface APIService {

    // Ajustá la ruta a como lo expone tu toto-backend
    // Ej: POST http://<host>:8080/api/stt/transcribe
    @Multipart
    @POST("api/stt")
    Call<TranscriptionResponse> transcribe(
            @Part MultipartBody.Part audio,
            @Part("language") RequestBody language,      // "es" por defecto
            @Part("userName") RequestBody userName       // opcional, para logging/telemetría
    );

    @POST("api/ask")
    Call<AskResponse> ask(@Body AskRequest body);

    @POST("/api/nlu/route")
    Call<NluRouteResponse> nluRoute(@Body NluRouteRequest req);
}
