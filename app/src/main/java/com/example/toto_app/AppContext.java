package com.example.toto_app;

import android.app.Application;
import android.content.Context;

import com.example.toto_app.services.BackendHealthManager;

public class AppContext extends Application {
    private static Context ctx;
    @Override public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
        BackendHealthManager.init(ctx);
    }
    public static Context get() { return ctx; }
}

