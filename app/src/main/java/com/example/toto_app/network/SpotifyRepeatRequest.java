package com.example.toto_app.network;
public class SpotifyRepeatRequest {
    public String state; // "track" | "context" | "off"
    public SpotifyRepeatRequest() {}
    public SpotifyRepeatRequest(String s) { this.state = s; }
}
