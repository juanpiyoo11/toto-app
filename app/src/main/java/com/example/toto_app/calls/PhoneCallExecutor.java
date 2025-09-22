package com.example.toto_app.calls;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public final class PhoneCallExecutor {
    private static final String TEMP_FG_CHANNEL_ID = "toto_temp_fg";
    private static final int TEMP_FG_NOTIFY_ID = 998711;

    private final Service svc;

    public PhoneCallExecutor(Service svc) { this.svc = svc; }

    public boolean placeDirectCall(String displayName, String number, boolean isDefaultDialer) {
        if (isDefaultDialer) {
            boolean startedFg = startTempForeground("Llamando a " + (displayName == null ? "" : displayName));
            try {
                TelecomManager tm = (TelecomManager) svc.getSystemService(Service.TELECOM_SERVICE);
                if (tm == null) return false;
                Uri uri = Uri.fromParts("tel", number, null);
                Bundle extras = new Bundle();
                tm.placeCall(uri, extras);
                return true;
            } catch (Throwable t) {
                Log.e("PhoneCallExecutor", "placeCall falló", t);
                return false;
            } finally {
                stopTempForeground();
            }
        }
        try {
            Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            svc.startActivity(call);
            return true;
        } catch (Throwable t) {
            Log.e("PhoneCallExecutor", "ACTION_CALL falló", t);
            return false;
        }
    }

    private boolean startTempForeground(String text) {
        try {
            NotificationManager nm = (NotificationManager) svc.getSystemService(Service.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        TEMP_FG_CHANNEL_ID, "Toto (tarea en curso)", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Uso temporal para ejecutar acciones");
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(svc, TEMP_FG_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Toto")
                    .setContentText(text == null ? "Ejecutando acción…" : text)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true);
            Notification n = b.build();

            svc.startForeground(
                    TEMP_FG_NOTIFY_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            );
            return true;
        } catch (IllegalArgumentException iae) {
            Log.e("PhoneCallExecutor", "FGS phoneCall no permitido por Manifest.", iae);
            return false;
        } catch (Throwable t) {
            Log.e("PhoneCallExecutor", "No se pudo iniciar FGS temporal", t);
            return false;
        }
    }

    private void stopTempForeground() {
        try { svc.stopForeground(Service.STOP_FOREGROUND_DETACH); } catch (Throwable ignore) {}
        try {
            NotificationManager nm = (NotificationManager) svc.getSystemService(Service.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(TEMP_FG_NOTIFY_ID);
        } catch (Throwable ignore) {}
    }
}
