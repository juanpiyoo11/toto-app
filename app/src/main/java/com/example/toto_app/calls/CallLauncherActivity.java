package com.example.toto_app.calls;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class CallLauncherActivity extends AppCompatActivity {

    private String number = "";
    private boolean callStarted = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        number = getIntent() != null ? getIntent().getStringExtra("number") : "";
        if (number == null) number = "";

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }

        if (number.isEmpty()) {
            finishNoAnim();
            return;
        }

        if (isLocked()) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= 26) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { startCallAndFinishOnce(); }
                    @Override public void onDismissError()     { startCallAndFinishOnce(); }
                    @Override public void onDismissCancelled() { finishNoAnim(); }
                });
            } else {
                startDialerAndFinishOnce();
            }
        } else {
            startCallAndFinishOnce();
        }
    }

    private boolean isLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            return km.isKeyguardLocked() || km.isDeviceLocked();
        } else {
            return km.isKeyguardLocked();
        }
    }

    private void startCallAndFinishOnce() {
        if (callStarted) { finishNoAnim(); return; }
        callStarted = true;

        boolean hasCallPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;

        Intent i = hasCallPerm
                ? new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
                : new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));

        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try { startActivity(i); } catch (Exception ignored) { }
        finishNoAnim();
    }

    private void startDialerAndFinishOnce() {
        if (callStarted) { finishNoAnim(); return; }
        callStarted = true;

        Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
        finishNoAnim();
    }

    private void finishNoAnim() {
        finish();
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }
}
