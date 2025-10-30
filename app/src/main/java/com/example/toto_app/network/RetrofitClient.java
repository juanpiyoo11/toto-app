package com.example.toto_app.network;

import android.content.Context;

import ar.edu.uade.toto_app.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class RetrofitClient {

    private static volatile APIService INSTANCE;
    private static Context appContext;

    private RetrofitClient() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static APIService api() {
        if (INSTANCE == null) {
            synchronized (RetrofitClient.class) {
                if (INSTANCE == null) {
                    HttpLoggingInterceptor log = new HttpLoggingInterceptor();
                    log.setLevel(HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                            .addInterceptor(log);

                    // Add AuthInterceptor if context is available
                    if (appContext != null) {
                        clientBuilder.addInterceptor(new AuthInterceptor(appContext));
                    }

                    OkHttpClient client = clientBuilder.build();

                    Retrofit r = new Retrofit.Builder()
                            .baseUrl(BuildConfig.BACKEND_BASE_URL)
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
