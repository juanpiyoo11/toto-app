package com.example.toto_app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.toto_app.services.WakeWordService;

import java.util.ArrayList;
import java.util.List;

import ar.edu.uade.toto_app.R;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_ALL_PERMS = 1001;
    private static final int REQ_IGNORE_BATTERY = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        requestNeededPermissionsAndStart();
    }

    private void requestNeededPermissionsAndStart() {
        List<String> needed = new ArrayList<>();

        // Micrófono (siempre)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        // Notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_ALL_PERMS);
        } else {
            // Todo listo → arrancamos servicio y sugerimos ignorar optimizaciones de batería
            startWakeWordService();
            maybeRequestIgnoreBatteryOptimizations();
        }
    }

    private void startWakeWordService() {
        Intent i = new Intent(this, WakeWordService.class);
        ContextCompat.startForegroundService(this, i);
        Toast.makeText(this, "Escuchando \"Toto\" en segundo plano", Toast.LENGTH_SHORT).show();
    }

    private void maybeRequestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_IGNORE_BATTERY);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(requestCode, perms, res);
        if (requestCode == REQ_ALL_PERMS) {
            boolean micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;

            if (micOk) {
                // Aunque el user niegue notificaciones, arrancamos igual
                // (pero idealmente que las habilite para que el FGS quede estable)
                startWakeWordService();
                maybeRequestIgnoreBatteryOptimizations();

                if (Build.VERSION.SDK_INT >= 33) {
                    boolean notifOk = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED;
                    if (!notifOk) {
                        Toast.makeText(this,
                                "Sugerencia: habilitá notificaciones para mayor estabilidad en segundo plano.",
                                Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Toast.makeText(this, "Se requiere permiso de micrófono", Toast.LENGTH_LONG).show();
            }
        }
    }
}
