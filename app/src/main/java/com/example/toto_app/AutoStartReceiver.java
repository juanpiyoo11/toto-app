package com.example.toto_app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.example.toto_app.services.FallDetectionService;
import com.example.toto_app.services.WakeWordService;
import com.example.toto_app.util.TokenManager;

public class AutoStartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        boolean boot = action.equals(Intent.ACTION_BOOT_COMPLETED);
        boolean replaced = action.equals(Intent.ACTION_MY_PACKAGE_REPLACED);

        if (boot || replaced) {
            // Check if user is authenticated before starting services
            TokenManager tokenManager = new TokenManager(context);
            String accessToken = tokenManager.getAccessToken();
            
            if (accessToken == null || accessToken.isEmpty()) {
                // User not authenticated, don't start services
                return;
            }
            
            // Solo si ya tenemos permiso de micrófono (no se puede pedir en background)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {

                Intent ww = new Intent(context, WakeWordService.class);
                ContextCompat.startForegroundService(context, ww);

                Intent yf = new Intent(context, FallDetectionService.class);
                ContextCompat.startForegroundService(context, yf);
            }
        }
    }
}
