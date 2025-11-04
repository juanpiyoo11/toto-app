package com.example.toto_app.network;

public class NluRouteResponse {
    public String intent;
    public double confidence;
    public boolean needs_confirmation;
    public Slots slots;
    public String clarifying_question;
    public String ack_tts;
    public String safety_notes;

    public static class Slots {
        public String contact_query;
        public Integer hour;
        public Integer minute;
        public String datetime_iso;
        public String message_text;
        public String app_name;

        public String reminder_title;
        public String reminder_type;
        public String repeat_pattern;
        public String query_reminder_type;
    }
}
