package com.example.toto_app.services;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;
import androidx.work.BackoffPolicy;

import ar.edu.uade.toto_app.BuildConfig;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


public final class BackendHealthManager {
    private static final String TAG = "BackendHealthManager";
    private static volatile BackendHealthManager INSTANCE;

    private final Context ctx;
    private final OkHttpClient client;
    private volatile boolean backendUp = true;
    private volatile long lastChecked = 0L;
    private static final long CACHE_MS = 5000L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private BackendHealthManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized void init(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new BackendHealthManager(ctx);
        }
    }

    public static BackendHealthManager get() {
        if (INSTANCE == null) throw new IllegalStateException("BackendHealthManager not initialized");
        return INSTANCE;
    }


    public synchronized boolean isBackendUp() {
        long now = SystemClock.elapsedRealtime();
        if ((now - lastChecked) < CACHE_MS) return backendUp;

        boolean up = performHealthCheck();
        setBackendUp(up);
        return up;
    }

    static boolean performCheckStatic(Context ctx) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .callTimeout(2, TimeUnit.SECONDS)
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build();
            String url = BuildConfig.BACKEND_BASE_URL + "health";
            Request req = new Request.Builder().url(url).get().build();
            Call c = client.newCall(req);
            try (Response r = c.execute()) {
                return r != null && r.isSuccessful();
            }
        } catch (IOException e) {
            Log.w(TAG, "health check failed: " + e.getMessage());
            return false;
        }
    }

    private boolean performHealthCheck() {
        return performCheckStatic(this.ctx);
    }

    private synchronized void setBackendUp(boolean up) {
        boolean prev = this.backendUp;
        this.backendUp = up;
        this.lastChecked = SystemClock.elapsedRealtime();
        if (!prev && up) {
            Log.i(TAG, "Backend recovered -> flushing pending emergencies");
            scheduler.execute(() -> {
                try { PendingEmergencyStore.get().flushPendingNow(); } catch (Exception e) { Log.e(TAG, "flush error", e); }
            });
        } else if (prev && !up) {
            Log.w(TAG, "Backend marked DOWN -> scheduling recovery worker");
            try {
                HealthCheckWorker.scheduleRetry(this.ctx, 3);
            } catch (Exception e) { Log.e(TAG, "error scheduling health worker", e); }
        }
    }


    public synchronized void notifyRecovered() {
        setBackendUp(true);
    }

    public void markFailure() {
        setBackendUp(false);
    }
 }
