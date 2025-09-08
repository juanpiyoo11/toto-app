package com.example.toto_app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.example.toto_app.falls.FallSignals;
import com.example.toto_app.services.InstructionService;

public class FallTriggerReceiver extends BroadcastReceiver {
    public static final String EXTRA_FALL_MODE = "fall_mode"; // reusa el que ya usás en InstructionService

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !FallSignals.ACTION_FALL_DETECTED.equals(intent.getAction())) return;

        String user = intent.getStringExtra(FallSignals.EXTRA_USER_NAME); // opcional
        Intent i = new Intent(ctx, InstructionService.class);
        i.putExtra("user_name", TextUtils.isEmpty(user) ? "Juan" : user);
        i.putExtra(EXTRA_FALL_MODE, "CHECK"); // <-- dispara el flujo de caída ya implementado en InstructionService
        ctx.startService(i);
    }
}
