package com.example.toto_app.network;

public class ReminderDTO {
    private Long id;
    private Long elderlyId;
    private String reminderType; // MEDICATION, APPOINTMENT, EVENT
    private String title;
    private String description;
    private String reminderTime; // ISO 8601 format
    private String repeatPattern; // DAILY, WEEKLY, MONTHLY, NONE
    
    // Medication-specific
    private String dosage;
    
    // Appointment-specific
    private String doctor;
    
    // Appointment and Event specific
    private String location;
    private Integer leadTimeMinutes;
    
    private Boolean active;
    private String createdAt;
    private String updatedAt;

    public ReminderDTO() {
    }

    public ReminderDTO(Long elderlyId, String reminderType, String title, String description, 
                      String reminderTime, String repeatPattern) {
        this.elderlyId = elderlyId;
        this.reminderType = reminderType;
        this.title = title;
        this.description = description;
        this.reminderTime = reminderTime;
        this.repeatPattern = repeatPattern;
        this.active = true;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReminderTime() {
        return reminderTime;
    }

    public void setReminderTime(String reminderTime) {
        this.reminderTime = reminderTime;
    }

    public String getRepeatPattern() {
        return repeatPattern;
    }

    public void setRepeatPattern(String repeatPattern) {
        this.repeatPattern = repeatPattern;
    }

    public String getDosage() {
        return dosage;
    }

    public void setDosage(String dosage) {
        this.dosage = dosage;
    }

    public String getDoctor() {
        return doctor;
    }

    public void setDoctor(String doctor) {
        this.doctor = doctor;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getLeadTimeMinutes() {
        return leadTimeMinutes;
    }

    public void setLeadTimeMinutes(Integer leadTimeMinutes) {
        this.leadTimeMinutes = leadTimeMinutes;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
