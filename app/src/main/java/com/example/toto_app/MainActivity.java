package com.example.toto_app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.toto_app.falls.FallSignals;
import com.example.toto_app.services.FallDetectionService;
import com.example.toto_app.services.WakeWordService;
import com.example.toto_app.util.TokenManager;
import com.example.toto_app.util.UserDataManager;

import java.util.ArrayList;
import java.util.List;

import ar.edu.uade.toto_app.R;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_ALL_PERMS       = 1001;
    private static final int REQ_CONTACTS_ONLY   = 1002;
    private static final int REQ_IGNORE_BATTERY  = 2001;
    private static final int REQ_ROLE_DIALER     = 5001;

    private TokenManager tokenManager;
    private UserDataManager userDataManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Check if user is logged in
        tokenManager = new TokenManager(this);
        if (tokenManager.getAccessToken() == null || tokenManager.getAccessToken().isEmpty()) {
            // Not logged in, redirect to login
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
        
        // Initialize UserDataManager and load user data from backend
        userDataManager = new UserDataManager(this);
        userDataManager.loadUserData(new UserDataManager.UserDataCallback() {
            @Override
            public void onSuccess() {
                Log.d("MainActivity", "User data loaded: " + userDataManager.getUserName());
            }

            @Override
            public void onError(String message) {
                Log.e("MainActivity", "Error loading user data: " + message);
                Toast.makeText(MainActivity.this, "Error cargando datos del usuario", Toast.LENGTH_SHORT).show();
            }
        });
        
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btnMockFall);
        btn.setOnClickListener(v -> {
            Toast.makeText(this, "Simulando caída…", Toast.LENGTH_SHORT).show();

            Intent i = new Intent(FallSignals.ACTION_FALL_DETECTED);
            // Muy importante para limitarlo a tu propia app
            i.setPackage(getPackageName());
            i.putExtra(FallSignals.EXTRA_SOURCE, "ui_button");
            i.putExtra(FallSignals.EXTRA_USER_NAME, userDataManager.getUserName());
            sendBroadcast(i);
        });

        Button btnPause = findViewById(R.id.btnPauseListening);
        btnPause.setOnClickListener(v -> {
            // Pausar wake word
            Intent i1 = new Intent(this, WakeWordService.class);
            i1.setAction(WakeWordService.ACTION_PAUSE_LISTEN);
            ContextCompat.startForegroundService(this, i1);

            // Pausar detección de caídas por sonido
            Intent i2 = new Intent(this, FallDetectionService.class);
            i2.setAction(FallDetectionService.ACTION_PAUSE_FALL);
            ContextCompat.startForegroundService(this, i2);

            Toast.makeText(this, "Toto en pausa (no escucha, sin detección de caídas)", Toast.LENGTH_SHORT).show();
        });

        Button btnResume = findViewById(R.id.btnResumeListening);
        btnResume.setOnClickListener(v -> {
            // Reanudar wake word
            Intent i1 = new Intent(this, WakeWordService.class);
            i1.setAction(WakeWordService.ACTION_RESUME_LISTEN);
            ContextCompat.startForegroundService(this, i1);

            // Reanudar detección de caídas por sonido
            Intent i2 = new Intent(this, FallDetectionService.class);
            i2.setAction(FallDetectionService.ACTION_RESUME_FALL);
            ContextCompat.startForegroundService(this, i2);

            Toast.makeText(this, "Toto volvió a escuchar (y reanudó caídas)", Toast.LENGTH_SHORT).show();
        });

        Button btnStopTts = findViewById(R.id.btnStopTts);
        btnStopTts.setOnClickListener(v -> {
            Intent i = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_STOP_TTS);
            startService(i);
        });

        Button btnLogout = findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Cerrar sesión")
                    .setMessage("¿Estás seguro que querés cerrar sesión?")
                    .setPositiveButton("Sí", (dialog, which) -> {
                        // Stop services before logout
                        stopService(new Intent(this, WakeWordService.class));
                        stopService(new Intent(this, FallDetectionService.class));
                        stopService(new Intent(this, com.example.toto_app.services.ReminderPollingService.class));
                        
                        // Clear tokens and user data
                        tokenManager.clearTokens();
                        userDataManager.clear();
                        
                        Intent intent = new Intent(this, LoginActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    })
                    .setNegativeButton("No", null)
                    .show();
        });

        Button btnNotif = findViewById(R.id.btnNotifAccess);
        btnNotif.setOnClickListener(v -> {
            // Open notification listener settings
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        // On startup, if Notification Listener not enabled, show a runtime-like popup to guide user
        if (!isNotificationListenerEnabled()) {
            showNotificationAccessPopup();
        }

        // Pide permisos base y arranca Toto
        requestNeededPermissionsAndStart();

        // Si nos abrió InstructionService para pedir SOLO contactos:
        if (getIntent() != null && getIntent().getBooleanExtra("request_contacts_perm", false)) {
            requestContactsIfNeeded();
        }
    }

    private void showNotificationAccessPopup() {
        new AlertDialog.Builder(this)
                .setTitle("Habilitar notificaciones")
                .setMessage("Para que Toto pueda detectar mensajes entrantes de WhatsApp, concedé acceso a notificaciones. Tocá Abrir configuración y activá Toto.")
                .setPositiveButton("Abrir configuración", (d, w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .setNegativeButton("Ahora no", null)
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("request_contacts_perm", false)) {
            requestContactsIfNeeded();
        }
    }

    private void requestNeededPermissionsAndStart() {
        List<String> needed = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_ALL_PERMS);
        } else {
            startWakeWordService();
            startFallDetectionService();
            startReminderPollingService();
            maybeRequestIgnoreBatteryOptimizations();
            maybeRequestDefaultDialerRole();
        }
    }

    private void requestContactsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    REQ_CONTACTS_ONLY
            );
        } else {
            Toast.makeText(this, "Permiso de contactos ya concedido.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startWakeWordService() {
        Intent i = new Intent(this, WakeWordService.class);
        ContextCompat.startForegroundService(this, i);
        Toast.makeText(this, "Escuchando \"Toto\" en segundo plano", Toast.LENGTH_SHORT).show();
    }

    private void startFallDetectionService() {
        Intent svc = new Intent(this, FallDetectionService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void startReminderPollingService() {
        Intent svc = new Intent(this, com.example.toto_app.services.ReminderPollingService.class);
        startService(svc);
        Log.d("MainActivity", "ReminderPollingService started");
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

    private void maybeRequestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    Intent roleIntent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivityForResult(roleIntent, REQ_ROLE_DIALER);
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
                startWakeWordService();
                startFallDetectionService();
                startReminderPollingService();
                maybeRequestIgnoreBatteryOptimizations();
                maybeRequestDefaultDialerRole();

                if (Build.VERSION.SDK_INT >= 33) {
                    boolean notifOk = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED;
                    if (!notifOk) {
                        Toast.makeText(this,
                                "Sugerencia: habilitá notificaciones para mayor estabilidad en segundo plano.",
                                Toast.LENGTH_LONG).show();
                    }
                }

                boolean contactsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED;
                if (!contactsOk) {
                    Toast.makeText(this,
                            "Podés dar permiso de Contactos para poder llamar por nombre.",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Se requiere permiso de micrófono para usar Toto.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_CONTACTS_ONLY) {
            boolean contactsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
            if (contactsOk) {
                Toast.makeText(this, "¡Listo! Ya puedo llamar a contactos por nombre.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Sin permiso de contactos no puedo buscar por nombre.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isNotificationListenerEnabled() {
        String pkg = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        return flat.contains(pkg);
    }
}
