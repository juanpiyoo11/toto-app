package com.example.toto_app.actions;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.database.Cursor;

import java.util.Locale;

public final class DeviceActions {

    private DeviceActions(){}

    /** Lanza el discador con el número buscado por nombre (requiere READ_CONTACTS para lookup). */
    public static boolean dialContactByName(Context ctx, String contactName) {
        String num = findPhoneByName(ctx.getContentResolver(), contactName);
        Intent i;
        if (num != null) {
            i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num));
        } else {
            // Si no lo encuentro, abro el dialer vacío para que el usuario decida
            i = new Intent(Intent.ACTION_DIAL);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return (num != null);
    }

    /** Busca un número por display name (contains, case-insensitive). */
    private static String findPhoneByName(ContentResolver cr, String name) {
        String[] projection = { ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME };
        String sel = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{"%" + name + "%"};
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Crea una alarma (suele mostrar UI de confirmación). */
    public static void setAlarm(Context ctx, int hour24, int minute, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label != null ? label : "Toto")
                // Algunos OEM soportan saltar UI, otros no:
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public static String hhmm(int h, int m) {
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }
}
