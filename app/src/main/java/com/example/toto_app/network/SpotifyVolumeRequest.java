package com.example.toto_app.network;
public class SpotifyVolumeRequest {
    public String value; // "40" | "up" | "down"
    public SpotifyVolumeRequest() {}
    public SpotifyVolumeRequest(String v) { this.value = v; }
}
