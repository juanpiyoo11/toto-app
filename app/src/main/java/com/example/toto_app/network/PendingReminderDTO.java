package com.example.toto_app.network;

public class PendingReminderDTO {
    private Long id;
    private Long elderlyId;
    private String reminderType;
    private String title;
    private String ttsMessage;
    private String scheduledFor;
    private Boolean isMedication;

    public PendingReminderDTO() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getElderlyId() {
        return elderlyId;
    }

    public void setElderlyId(Long elderlyId) {
        this.elderlyId = elderlyId;
    }

    public String getReminderType() {
        return reminderType;
    }

    public void setReminderType(String reminderType) {
        this.reminderType = reminderType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTtsMessage() {
        return ttsMessage;
    }

    public void setTtsMessage(String ttsMessage) {
        this.ttsMessage = ttsMessage;
    }

    public String getScheduledFor() {
        return scheduledFor;
    }

    public void setScheduledFor(String scheduledFor) {
        this.scheduledFor = scheduledFor;
    }

    public Boolean getIsMedication() {
        return isMedication;
    }

    public void setIsMedication(Boolean isMedication) {
        this.isMedication = isMedication;
    }
}
