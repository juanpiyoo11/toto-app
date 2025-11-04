package com.example.toto_app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.toto_app.network.APIService;
import com.example.toto_app.network.EmergencyContactDTO;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.UserProfileDTO;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UserDataManager {
    private static final String TAG = "UserDataManager";
    private static final String PREFS_NAME = "UserDataPrefs";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USER_NAME = "userName";
    private static final String KEY_USER_PHONE = "userPhone";
    private static final String KEY_EMERGENCY_NAME = "emergencyName";
    private static final String KEY_EMERGENCY_PHONE = "emergencyPhone";
    private static final String KEY_ALL_EMERGENCY_CONTACTS = "allEmergencyContacts";
    
    private final SharedPreferences prefs;
    private final APIService api;
    private final Gson gson;
    
    private Long userId;
    private String userName;
    private String userPhone;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private List<EmergencyContactDTO> allEmergencyContacts;
    
    public UserDataManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.api = RetrofitClient.api();
        this.gson = new Gson();
        loadFromPreferences();
    }
    

    public void loadUserData(final UserDataCallback callback) {
        api.getUserProfile().enqueue(new Callback<UserProfileDTO>() {
            @Override
            public void onResponse(Call<UserProfileDTO> call, Response<UserProfileDTO> response) {
                if (response.isSuccessful() && response.body() != null) {
                    UserProfileDTO profile = response.body();
                    userId = profile.getId();
                    userName = profile.getName();
                    userPhone = profile.getPhone();

                    prefs.edit()
                            .putLong(KEY_USER_ID, userId != null ? userId : 0L)
                            .putString(KEY_USER_NAME, userName)
                            .putString(KEY_USER_PHONE, userPhone)
                            .apply();
                    
                    Log.d(TAG, "User profile loaded: " + userName);

                    loadEmergencyContacts(callback);
                } else {
                    Log.e(TAG, "Failed to load user profile: " + response.code());
                    if (callback != null) callback.onError("Error cargando perfil");
                }
            }

            @Override
            public void onFailure(Call<UserProfileDTO> call, Throwable t) {
                Log.e(TAG, "Network error loading user profile", t);
                if (callback != null) callback.onError("Error de conexión");
            }
        });
    }
    
    private void loadEmergencyContacts(final UserDataCallback callback) {
        api.getEmergencyContacts().enqueue(new Callback<List<EmergencyContactDTO>>() {
            @Override
            public void onResponse(Call<List<EmergencyContactDTO>> call, Response<List<EmergencyContactDTO>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<EmergencyContactDTO> contacts = response.body();

                    allEmergencyContacts = contacts;
                    String contactsJson = gson.toJson(contacts);
                    
                    if (!contacts.isEmpty()) {
                        EmergencyContactDTO primary = contacts.get(0);
                        emergencyContactName = primary.getName();
                        emergencyContactPhone = primary.getPhone();

                        prefs.edit()
                                .putString(KEY_EMERGENCY_NAME, emergencyContactName)
                                .putString(KEY_EMERGENCY_PHONE, emergencyContactPhone)
                                .putString(KEY_ALL_EMERGENCY_CONTACTS, contactsJson)
                                .apply();
                        
                        Log.d(TAG, "Emergency contacts loaded: " + contacts.size() + " contacts");
                    } else {
                        Log.w(TAG, "No emergency contacts found");
                    }
                    
                    if (callback != null) callback.onSuccess();
                } else {
                    Log.e(TAG, "Failed to load emergency contacts: " + response.code());
                    if (callback != null) callback.onError("Error cargando contactos de emergencia");
                }
            }

            @Override
            public void onFailure(Call<List<EmergencyContactDTO>> call, Throwable t) {
                Log.e(TAG, "Network error loading emergency contacts", t);
                if (callback != null) callback.onError("Error de conexión");
            }
        });
    }
    
    private void loadFromPreferences() {
        long id = prefs.getLong(KEY_USER_ID, 0L);
        userId = id > 0 ? id : null;
        userName = prefs.getString(KEY_USER_NAME, "Usuario");
        userPhone = prefs.getString(KEY_USER_PHONE, null);
        emergencyContactName = prefs.getString(KEY_EMERGENCY_NAME, null);
        emergencyContactPhone = prefs.getString(KEY_EMERGENCY_PHONE, null);

        String contactsJson = prefs.getString(KEY_ALL_EMERGENCY_CONTACTS, null);
        if (contactsJson != null) {
            Type listType = new TypeToken<ArrayList<EmergencyContactDTO>>(){}.getType();
            allEmergencyContacts = gson.fromJson(contactsJson, listType);
        } else {
            allEmergencyContacts = new ArrayList<>();
        }
    }
    
    public Long getUserId() {
        long id = prefs.getLong(KEY_USER_ID, 0L);
        return id > 0 ? id : null;
    }
    
    public String getUserName() {
        String name = prefs.getString(KEY_USER_NAME, "Usuario");
        return name != null ? name : "Usuario";
    }
    
    public String getUserPhone() {
        return prefs.getString(KEY_USER_PHONE, userPhone);
    }
    
    public String getEmergencyContactName() {
        return prefs.getString(KEY_EMERGENCY_NAME, emergencyContactName);
    }
    
    public String getEmergencyContactPhone() {
        return prefs.getString(KEY_EMERGENCY_PHONE, emergencyContactPhone);
    }
    
    public boolean hasEmergencyContact() {
        return emergencyContactName != null && emergencyContactPhone != null;
    }
    

    public List<EmergencyContactDTO> getAllEmergencyContacts() {
        String contactsJson = prefs.getString(KEY_ALL_EMERGENCY_CONTACTS, null);
        if (contactsJson != null) {
            Type listType = new TypeToken<ArrayList<EmergencyContactDTO>>(){}.getType();
            return gson.fromJson(contactsJson, listType);
        }
        return new ArrayList<>();
    }
    
    public void clear() {
        prefs.edit().clear().apply();
        userName = null;
        userPhone = null;
        emergencyContactName = null;
        emergencyContactPhone = null;
        allEmergencyContacts = new ArrayList<>();
    }
    
    public interface UserDataCallback {
        void onSuccess();
        void onError(String message);
    }
}
