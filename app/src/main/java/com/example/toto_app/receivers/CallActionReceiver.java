package com.example.toto_app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.example.toto_app.actions.DeviceActions;

public class CallActionReceiver extends BroadcastReceiver {
    public static final String ACTION_CALL_NOW = "com.example.toto_app.ACTION_CALL_NOW";
    public static final String EXTRA_NUMBER = "number";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_CALL_NOW.equals(intent.getAction())) return;
        String number = intent.getStringExtra(EXTRA_NUMBER);
        if (number == null || number.trim().isEmpty()) {
            Toast.makeText(context, "No hay número para llamar", Toast.LENGTH_SHORT).show();
            return;
        }
        // Dispara la llamada o abre el dialer según permisos
        DeviceActions.startCallOrDial(context, number);
    }
}
