package com.example.toto_app.network;

public class SpotifyResponse {
    public String status;     // "ok" | "error" (según tu backend)
    public String message;    // opcional
    public Boolean ok;        // opcional (true/false) por si preferís boolean
}
