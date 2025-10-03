package com.example.toto_app.network;

public class SpotifyStatus {
    public boolean connected;
    public Boolean premium;          // puede venir null si no se pudo leer /me
    public Integer deviceCount;      // puede venir null
    public Boolean hasActiveDevice;  // puede venir null
    public String suggestedDeviceId; // hint opcional
    public String loginUrl;          // siempre presente
    public String error;             // ej. token_refresh_failed
}
