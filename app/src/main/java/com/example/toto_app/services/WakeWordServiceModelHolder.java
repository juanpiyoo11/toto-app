package com.example.toto_app.services;

import org.vosk.Model;

public final class WakeWordServiceModelHolder {
    private static volatile Model MODEL;
    public static void setModel(Model m) { MODEL = m; }
    public static Model getModel() { return MODEL; }
}

