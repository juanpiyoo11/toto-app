package com.example.toto_app.network;

import com.google.gson.annotations.SerializedName;

public class EmergencyContactDTO {
    @SerializedName("id")
    private Long id;
    
    @SerializedName("name")
    private String name;
    
    @SerializedName("phone")
    private String phone;
    
    @SerializedName("relationship")
    private String relationship;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getRelationship() {
        return relationship;
    }
}
