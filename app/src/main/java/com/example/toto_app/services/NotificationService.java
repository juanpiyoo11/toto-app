package com.example.toto_app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
public final class NotificationService {
    private NotificationService(){}

    private static final String CALL_CHANNEL_ID = "toto_call_confirm";
    private static final int CALL_NOTIFY_ID_BASE = 223400;
    private static final String CHANNEL_ACTIONS_ID = "toto-actions";
    private static final String CHANNEL_ACTIONS_NAME = "Acciones de Toto";
    private static final int NOTIF_ID_ACTION_GENERIC = 7001;

    public static void showCallNotification(Service svc, String name, @Nullable String number, boolean hasCallPerm) {
        NotificationManager nm = (NotificationManager) svc.getSystemService(Service.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CALL_CHANNEL_ID, "Llamar contacto", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tocar para llamar");
            nm.createNotificationChannel(ch);
        }

        Intent tapIntent;
        if (number != null && !number.isEmpty() && hasCallPerm) {
            tapIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
        } else if (number != null && !number.isEmpty()) {
            tapIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        } else {
            tapIntent = new Intent(Intent.ACTION_DIAL);
        }
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = (int) (System.currentTimeMillis() & 0xFFFFFF);
        PendingIntent contentPi = PendingIntent.getActivity(
                svc, reqCode, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String who = (name != null && !name.isEmpty()) ? name : "Contacto";

        Notification n = new NotificationCompat.Builder(svc, CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Llamar a " + who)
                .setContentText(number != null && !number.isEmpty() ? number : "Tocar para abrir el marcador")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .build();

        int notifyId = CALL_NOTIFY_ID_BASE + (who.hashCode() & 0x0FFF);
        nm.notify(notifyId, n);
    }

    public static void simpleActionNotification(Context ctx,
                                                String title,
                                                String text,
                                                PendingIntent action) {
        ensureActionsChannel(ctx);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ACTIONS_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(action)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_ACTION_GENERIC, b.build());
    }

    private static void ensureActionsChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ACTIONS_ID,
                    CHANNEL_ACTIONS_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.enableLights(true);
            ch.setLightColor(Color.GREEN);
            ch.enableVibration(true);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }
}
