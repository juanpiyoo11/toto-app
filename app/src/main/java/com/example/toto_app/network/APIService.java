package com.example.toto_app.network;

import com.google.gson.JsonObject;

import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface APIService {

    @Multipart
    @POST("api/stt")
    Call<TranscriptionResponse> transcribe(
            @Part MultipartBody.Part audio,
            @Part("language") RequestBody language,
            @Part("userName") RequestBody userName
    );

    @POST("api/ask")
    Call<AskResponse> ask(@Body AskRequest body);

    @POST("api/nlu/route")
    Call<NluRouteResponse> nluRoute(@Body NluRouteRequest req);

    @POST("api/whatsapp/send")
    Call<WhatsAppSendResponse> waSend(@Body WhatsAppSendRequest req);

    @POST("/api/spotify/pause")
    retrofit2.Call<SpotifyResponse> spotifyPause();

    @POST("/api/spotify/next")
    retrofit2.Call<SpotifyResponse> spotifyNext();

    @POST("/api/spotify/previous")
    retrofit2.Call<SpotifyResponse> spotifyPrev();

    @POST("/api/spotify/volume")
    retrofit2.Call<SpotifyResponse> spotifyVolume(@Body SpotifyVolumeRequest body);

    @POST("/api/spotify/shuffle")
    retrofit2.Call<SpotifyResponse> spotifyShuffle(@Body SpotifyShuffleRequest body);

    @POST("/api/spotify/repeat")
    retrofit2.Call<SpotifyResponse> spotifyRepeat(@Body SpotifyRepeatRequest body);

    @GET("/api/spotify/status")
    Call<SpotifyStatus> spotifyStatus();

    @POST("/api/spotify/play")
    Call<JsonObject> spotifyPlay(@Body Map<String, String> body);
}
