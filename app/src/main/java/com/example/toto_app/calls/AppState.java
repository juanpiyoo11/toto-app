package com.example.toto_app.calls;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.os.Build;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.List;

public final class AppState {
    private AppState(){}

    public static boolean isAppInForeground(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            final String myPkg = ctx.getPackageName();
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p == null || p.pkgList == null) continue;
                boolean isMine = false;
                for (String pkg : p.pkgList) {
                    if (myPkg.equals(pkg)) { isMine = true; break; }
                }
                if (!isMine) continue;
                int imp = p.importance;
                if (imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        || imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w("AppState", "isAppInForeground() fallo, asumo background", t);
        }
        return false;
    }

    public static boolean isDeviceLocked(Context ctx) {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            return km.isDeviceLocked() || km.isKeyguardLocked();
        } else {
            return km.isKeyguardLocked();
        }
    }

    public static boolean isDefaultDialer(Context ctx) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) ctx.getSystemService(Context.ROLE_SERVICE);
                return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER);
            } catch (Throwable ignored) {}
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                String pkg = tm.getDefaultDialerPackage();
                return ctx.getPackageName().equals(pkg);
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
