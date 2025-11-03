package com.example.toto_app.network;

import com.google.gson.annotations.SerializedName;

public class UserProfileDTO {
    @SerializedName("id")
    private Long id;
    
    @SerializedName("name")
    private String name;
    
    @SerializedName("email")
    private String email;
    
    @SerializedName("phone")
    private String phone;
    
    @SerializedName("role")
    private String role;
    
    @SerializedName("address")
    private String address;
    
    @SerializedName("birthdate")
    private String birthdate;
    
    @SerializedName("medicalInfo")
    private String medicalInfo;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getRole() {
        return role;
    }

    public String getAddress() {
        return address;
    }

    public String getBirthdate() {
        return birthdate;
    }

    public String getMedicalInfo() {
        return medicalInfo;
    }
}
