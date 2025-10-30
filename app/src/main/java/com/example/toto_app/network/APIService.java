package com.example.toto_app.network;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface APIService {

    // Authentication endpoints
    @POST("api/auth/login")
    Call<LoginResponse> login(@Body LoginRequest request);

    @POST("api/auth/register")
    Call<LoginResponse> register(@Body RegisterRequest request);

    @POST("api/auth/refresh")
    Call<LoginResponse> refreshToken(@Body RefreshTokenRequest request);

    // Contact endpoints
    @GET("api/contacts")
    Call<List<ContactDTO>> getContacts(@Query("elderlyId") Long elderlyId);

    @GET("api/contacts/{id}")
    Call<ContactDTO> getContactById(@Path("id") Long id);

    @POST("api/contacts")
    Call<ContactDTO> createContact(@Body ContactDTO contact);

    @PUT("api/contacts/{id}")
    Call<ContactDTO> updateContact(@Path("id") Long id, @Body ContactDTO contact);

    @DELETE("api/contacts/{id}")
    Call<Void> deleteContact(@Path("id") Long id);

    // Reminder endpoints
    @GET("api/reminders")
    Call<List<ReminderDTO>> getReminders(
            @Query("elderlyId") Long elderlyId,
            @Query("activeOnly") Boolean activeOnly
    );

    @GET("api/reminders/{id}")
    Call<ReminderDTO> getReminderById(@Path("id") Long id);

    @POST("api/reminders")
    Call<ReminderDTO> createReminder(@Body ReminderDTO reminder);

    @PUT("api/reminders/{id}")
    Call<ReminderDTO> updateReminder(@Path("id") Long id, @Body ReminderDTO reminder);

    @PATCH("api/reminders/{id}/toggle")
    Call<ReminderDTO> toggleReminderActive(@Path("id") Long id);

    @DELETE("api/reminders/{id}")
    Call<Void> deleteReminder(@Path("id") Long id);

    // History endpoints
    @GET("api/history")
    Call<List<HistoryEventDTO>> getHistory(
            @Query("userId") Long userId,
            @Query("start") String start,
            @Query("end") String end
    );

    @GET("api/history/{id}")
    Call<HistoryEventDTO> getHistoryEventById(@Path("id") Long id);

    @POST("api/history")
    Call<HistoryEventDTO> createHistoryEvent(@Body HistoryEventDTO event);

    // Existing endpoints
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

    @POST("/api/spotify/play")
    retrofit2.Call<SpotifyResponse> spotifyPlay(@Body SpotifyPlayRequest body);

    @POST("/api/spotify/pause")
    retrofit2.Call<SpotifyResponse> spotifyPause();

    @POST("/api/spotify/resume")
    retrofit2.Call<SpotifyResponse> spotifyResume();

    @POST("/api/spotify/next")
    retrofit2.Call<SpotifyResponse> spotifyNext();

    @POST("/api/spotify/prev")
    retrofit2.Call<SpotifyResponse> spotifyPrev();

    @POST("/api/spotify/volume")
    retrofit2.Call<SpotifyResponse> spotifyVolume(@Body SpotifyVolumeRequest body);

    @POST("/api/spotify/shuffle")
    retrofit2.Call<SpotifyResponse> spotifyShuffle(@Body SpotifyShuffleRequest body);

    @POST("/api/spotify/repeat")
    retrofit2.Call<SpotifyResponse> spotifyRepeat(@Body SpotifyRepeatRequest body);

    @GET("/api/spotify/status")
    Call<SpotifyStatus> spotifyStatus();

    @GET("/api/spotify/login-url")
    Call<Map<String, String>> spotifyLoginUrl();

    @POST("/api/spotify/play")
    Call<JsonObject> spotifyPlay(@Body Map<String, String> body);
}
