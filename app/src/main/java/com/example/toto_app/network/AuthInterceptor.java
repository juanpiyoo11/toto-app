package com.example.toto_app.network;

import android.content.Context;
import com.example.toto_app.util.TokenManager;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class AuthInterceptor implements Interceptor {
    private final TokenManager tokenManager;

    public AuthInterceptor(Context context) {
        this.tokenManager = new TokenManager(context);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        String accessToken = tokenManager.getAccessToken();

        // Skip auth ONLY for public endpoints (login/register)
        String path = originalRequest.url().encodedPath();
        if (path.contains("/api/auth/")) {
            return chain.proceed(originalRequest);
        }

        // Add Authorization header if token exists for ALL other endpoints
        if (accessToken != null && !accessToken.isEmpty()) {
            Request authenticatedRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer " + accessToken)
                    .build();
            return chain.proceed(authenticatedRequest);
        }

        // If no token, proceed anyway (backend will handle 401/403)
        return chain.proceed(originalRequest);
    }
}
