package com.example.toto_app;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.toto_app.services.WakeWordService;

import java.util.ArrayList;
import java.util.List;

import ar.edu.uade.toto_app.R;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_ALL_PERMS       = 1001;
    private static final int REQ_CONTACTS_ONLY   = 1002;
    private static final int REQ_IGNORE_BATTERY  = 2001;
    private static final int REQ_ROLE_DIALER     = 5001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Pide permisos base y arranca Toto
        requestNeededPermissionsAndStart();

        // Si nos abrió InstructionService para pedir SOLO contactos:
        if (getIntent() != null && getIntent().getBooleanExtra("request_contacts_perm", false)) {
            requestContactsIfNeeded();
        }
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

        // Micrófono (siempre)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        // Contactos (para “llamá a …”)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS);
        }

        // Llamadas (para ACTION_CALL si somos dialer)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE);
        }

        // Notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_ALL_PERMS);
        } else {
            // Todo listo → arrancamos servicio y sugerimos optimizaciones
            startWakeWordService();
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

    /** Pedí el rol de dialer para poder iniciar llamadas directas (sin abrir el marcador). */
    private void maybeRequestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    Intent roleIntent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivityForResult(roleIntent, REQ_ROLE_DIALER);
                } catch (Exception ignored) {
                    // Algunos OEMs pueden bloquear el intent → seguimos sin llamada directa
                }
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

    @Override
    @SuppressWarnings("deprecation") // para startActivityForResult en RoleManager/batería
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ROLE_DIALER) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, "Toto ahora es tu marcador predeterminado. Podré iniciar llamadas directas.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "No soy el marcador predeterminado. Abriré el marcador para confirmar llamadas.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IGNORE_BATTERY) {
            // Nada crítico; solo feedback
            Toast.makeText(this, "Optimización de batería ajustada.", Toast.LENGTH_SHORT).show();
        }
    }
}
