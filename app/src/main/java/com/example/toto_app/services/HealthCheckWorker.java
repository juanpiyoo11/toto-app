package com.example.toto_app.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

public class HealthCheckWorker extends Worker {
    private static final String TAG = "HealthCheckWorker";

    public HealthCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            boolean ok = com.example.toto_app.services.BackendHealthManager.performCheckStatic(getApplicationContext());
            if (ok) {
                Log.i(TAG, "health check ok");
                return Result.success();
            }
        } catch (Throwable t) {
            Log.w(TAG, "health check worker exception", t);
        }
        // reintentar con backoff
        return Result.retry();
    }

    public static void scheduleRetry(Context ctx, long delaySeconds) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(HealthCheckWorker.class)
                .setInitialDelay(Math.max(1, delaySeconds), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }
}

