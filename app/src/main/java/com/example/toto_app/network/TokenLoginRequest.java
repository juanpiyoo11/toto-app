package com.example.toto_app.network;

public class TokenLoginRequest {
    private String token;

    public TokenLoginRequest(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
