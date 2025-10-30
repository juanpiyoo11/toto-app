package com.example.toto_app.network;

public class HistoryEventDTO {
    private Long id;
    private Long userId;
    private String eventType;
    private String details;
    private String timestamp;

    public HistoryEventDTO() {
    }

    public HistoryEventDTO(Long userId, String eventType, String details) {
        this.userId = userId;
        this.eventType = eventType;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
