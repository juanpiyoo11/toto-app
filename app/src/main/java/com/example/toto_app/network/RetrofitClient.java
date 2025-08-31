package com.example.toto_app.network;

import ar.edu.uade.toto_app.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class RetrofitClient {

    private static volatile APIService INSTANCE;

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
                            .baseUrl(BuildConfig.BACKEND_BASE_URL) // debe terminar en '/'
//                          .baseUrl(BuildConfig.TOTO_API_BASE)     // (si preferís este nombre)
                            .client(client)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();

                    INSTANCE = r.create(APIService.class);
                }
            }
        }
        return INSTANCE;
    }
}
