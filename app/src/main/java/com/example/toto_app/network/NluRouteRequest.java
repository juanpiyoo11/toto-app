package com.example.toto_app.network;

import java.util.Map;

public class NluRouteRequest {
    public String text;
    public String locale;
    public Map<String,Object> context;
    public Map<String,Object> hints;
}
