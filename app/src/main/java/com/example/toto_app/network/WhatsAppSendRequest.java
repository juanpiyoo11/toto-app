package com.example.toto_app.network;

public class WhatsAppSendRequest {
    public String to;
    public String text;
    public Boolean previewUrl;

    public WhatsAppSendRequest(String to, String text, Boolean previewUrl) {
        this.to = to;
        this.text = text;
        this.previewUrl = previewUrl;
    }
}
