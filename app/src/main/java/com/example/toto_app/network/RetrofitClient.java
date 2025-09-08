package com.example.toto_app.network;

import ar.edu.uade.toto_app.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class RetrofitClient {

    private static volatile APIService INSTANCE;       // cliente "normal"
    private static volatile APIService FAST_INSTANCE;  // timeouts cortos (STT / NLU)

    private RetrofitClient() {}

    public static APIService api() {
        if (INSTANCE == null) {
            synchronized (RetrofitClient.class) {
                if (INSTANCE == null) {
                    HttpLoggingInterceptor log = new HttpLoggingInterceptor();
                    log.setLevel(HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient client = new OkHttpClient.Builder()
                            .addInterceptor(log)
                            .build();

                    Retrofit r = new Retrofit.Builder()
                            .baseUrl(BuildConfig.BACKEND_BASE_URL) // ¡debe terminar en '/'!
                            .client(client)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();

                    INSTANCE = r.create(APIService.class);
                }
            }
        }
        return INSTANCE;
    }
    public static APIService apiFast() {
        if (FAST_INSTANCE == null) {
            synchronized (RetrofitClient.class) {
                if (FAST_INSTANCE == null) {
                    HttpLoggingInterceptor log = new HttpLoggingInterceptor();
                    log.setLevel(HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient fast = new OkHttpClient.Builder()
                            .connectTimeout(1500, TimeUnit.MILLISECONDS)
                            .readTimeout(2500, TimeUnit.MILLISECONDS)
                            .writeTimeout(2500, TimeUnit.MILLISECONDS)
                            .callTimeout(3000, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .addInterceptor(log)
                            .build();

                    Retrofit rFast = new Retrofit.Builder()
                            .baseUrl(BuildConfig.BACKEND_BASE_URL)
                            .client(fast)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();

                    FAST_INSTANCE = rFast.create(APIService.class);
                }
            }
        }
        return FAST_INSTANCE;
    }
}
