package com.example.toto_app.actions;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DeviceActions {
    private DeviceActions(){}

    public enum AlarmResult { SET_SILENT, UI_ACTION_REQUIRED, FAILED }

    private static final String CH_ALARM_CONFIRM = "toto_alarm_confirm";

    public static final class ResolvedContact {
        public final String name;
        public final String number;
        public final double score;
        public ResolvedContact(String name, String number, double score) {
            this.name = name; this.number = number; this.score = score;
        }
    }

    @Nullable
    public static ResolvedContact resolveContactByNameFuzzy(Context ctx, String query) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        String q = cleanContactQuery(query);
        if (q.isEmpty()) return null;

        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        Cursor c = null;
        List<ResolvedContact> candidates = new ArrayList<>();
        try {
            c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null);
            if (c == null) return null;

            while (c.moveToNext()) {
                String name = c.getString(0);
                String number = c.getString(1);
                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(number)) continue;

                String norm = stripAccents(name).toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ").trim();
                double score = similarity(q, norm);
                candidates.add(new ResolvedContact(name, number, score));
            }
        } catch (SecurityException ignored) {
            return null;
        } finally {
            if (c != null) c.close();
        }

        ResolvedContact best = null;
        for (ResolvedContact cand : candidates) {
            if (best == null || cand.score > best.score) best = cand;
        }
        return (best != null && best.score >= 0.66) ? best : null;
    }

    public static void startCallOrDial(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) {
            openDialerEmpty(ctx);
            return;
        }
        Intent intent;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
        } else {
            intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    public static void openDialerEmpty(Context ctx) {
        Intent i = new Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Nullable
    public static String dialContactByNameFuzzy(Context ctx, String query) {
        ResolvedContact rc = resolveContactByNameFuzzy(ctx, query);
        if (rc != null) {
            startCallOrDial(ctx, rc.number);
            return rc.name;
        }
        openDialerEmpty(ctx);
        return null;
    }

    public static AlarmResult setAlarm(Context ctx, int hour24, int minute, String baseLabel) {
        hour24  = Math.max(0, Math.min(23, hour24));
        minute  = Math.max(0, Math.min(59, minute));

        String whenText = hhmm(hour24, minute);
        String labelBase = (baseLabel != null && !baseLabel.isEmpty()) ? baseLabel : "Toto";
        String uniqueSuffix = " • " + (SystemClock.uptimeMillis() % 10000);
        String label = labelBase + " " + whenText + uniqueSuffix;

        boolean isSamsung = "samsung".equalsIgnoreCase(Build.MANUFACTURER);
        String targetPkg = selectClockPackage(ctx);

        Intent silent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_VIBRATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (targetPkg != null) silent.setPackage(targetPkg);

        if (!isSamsung) {
            try {
                ctx.startActivity(silent);
                return AlarmResult.SET_SILENT;
            } catch (SecurityException | ActivityNotFoundException e) {
            }
        } else {
            }

        Intent withUi = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .putExtra(AlarmClock.EXTRA_VIBRATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (targetPkg != null) withUi.setPackage(targetPkg);

        postConfirmAlarmNotification(ctx, withUi, whenText);
        return AlarmResult.UI_ACTION_REQUIRED;
    }

    private static String selectClockPackage(Context ctx) {
        if (isPkgInstalled(ctx, "com.sec.android.app.clockpackage")) return "com.sec.android.app.clockpackage";
        if (isPkgInstalled(ctx, "com.google.android.deskclock")) return "com.google.android.deskclock";
        return null;
    }

    private static void postConfirmAlarmNotification(Context ctx, Intent clockIntent, String whenText) {
        ensureAlarmChannel(ctx);

        PendingIntent pi = PendingIntent.getActivity(
                ctx,
                (int) (SystemClock.uptimeMillis() & 0x7FFFFFFF),
                clockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification n = new NotificationCompat.Builder(ctx, CH_ALARM_CONFIRM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Confirmá la alarma")
                .setContentText("Tocá para crear la alarma de las " + whenText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(new NotificationCompat.Action(
                        android.R.drawable.ic_menu_add,
                        "Abrir reloj",
                        pi
                ))
                .build();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            int nid = (int) (SystemClock.uptimeMillis() & 0x7FFFFFFF);
            nm.notify(nid, n);
        }
    }

    private static void ensureAlarmChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = nm.getNotificationChannel(CH_ALARM_CONFIRM);
        if (ch == null) {
            ch = new NotificationChannel(
                    CH_ALARM_CONFIRM,
                    "Confirmar alarmas",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Notificaciones para confirmar creación de alarmas en el reloj");
            nm.createNotificationChannel(ch);
        }
    }

    public static String hhmm(int h, int m) {
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }

    private static boolean isPkgInstalled(Context ctx, String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception ignored) { return false; }
    }

    private static String cleanContactQuery(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^(llama|llamá|llamame|llamame a)\\s+", "");
        s = s.replaceFirst("^(a|al|la|el)\\s+", "");
        s = stripAccents(s);
        s = s.replaceAll("[^a-zñ0-9\\s]", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String stripAccents(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (b.startsWith(a)) return 0.95;
        if (b.contains(a)) return 0.90;
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0.0;
        return 1.0 - (dist / (double) max);
    }

    private static int levenshtein(String s1, String s2) {
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) prev[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;
            char c1 = s1.charAt(i - 1);
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (c1 == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[s2.length()];
    }
}
