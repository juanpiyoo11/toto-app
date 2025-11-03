package com.example.toto_app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.toto_app.network.APIService;
import com.example.toto_app.network.EmergencyContactDTO;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.UserProfileDTO;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Manages user data loaded from backend.
 * Provides access to user profile and emergency contacts throughout the app.
 */
public class UserDataManager {
    private static final String TAG = "UserDataManager";
    private static final String PREFS_NAME = "UserDataPrefs";
    private static final String KEY_USER_NAME = "userName";
    private static final String KEY_USER_PHONE = "userPhone";
    private static final String KEY_EMERGENCY_NAME = "emergencyName";
    private static final String KEY_EMERGENCY_PHONE = "emergencyPhone";
    
    private final SharedPreferences prefs;
    private final APIService api;
    
    private String userName;
    private String userPhone;
    private String emergencyContactName;
    private String emergencyContactPhone;
    
    public UserDataManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.api = RetrofitClient.api();
        loadFromPreferences();
    }
    
    /**
     * Load user data from backend and cache it locally.
     * Should be called after login.
     */
    public void loadUserData(final UserDataCallback callback) {
        // Load user profile
        api.getUserProfile().enqueue(new Callback<UserProfileDTO>() {
            @Override
            public void onResponse(Call<UserProfileDTO> call, Response<UserProfileDTO> response) {
                if (response.isSuccessful() && response.body() != null) {
                    UserProfileDTO profile = response.body();
                    userName = profile.getName();
                    userPhone = profile.getPhone();
                    
                    // Save to preferences
                    prefs.edit()
                            .putString(KEY_USER_NAME, userName)
                            .putString(KEY_USER_PHONE, userPhone)
                            .apply();
                    
                    Log.d(TAG, "User profile loaded: " + userName);
                    
                    // Now load emergency contacts
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
                    
                    if (!contacts.isEmpty()) {
                        // Use first emergency contact
                        EmergencyContactDTO primary = contacts.get(0);
                        emergencyContactName = primary.getName();
                        emergencyContactPhone = primary.getPhone();
                        
                        // Save to preferences
                        prefs.edit()
                                .putString(KEY_EMERGENCY_NAME, emergencyContactName)
                                .putString(KEY_EMERGENCY_PHONE, emergencyContactPhone)
                                .apply();
                        
                        Log.d(TAG, "Emergency contact loaded: " + emergencyContactName);
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
        userName = prefs.getString(KEY_USER_NAME, "Usuario");
        userPhone = prefs.getString(KEY_USER_PHONE, null);
        emergencyContactName = prefs.getString(KEY_EMERGENCY_NAME, null);
        emergencyContactPhone = prefs.getString(KEY_EMERGENCY_PHONE, null);
    }
    
    public String getUserName() {
        return userName != null ? userName : "Usuario";
    }
    
    public String getUserPhone() {
        return userPhone;
    }
    
    public String getEmergencyContactName() {
        return emergencyContactName;
    }
    
    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }
    
    public boolean hasEmergencyContact() {
        return emergencyContactName != null && emergencyContactPhone != null;
    }
    
    public void clear() {
        prefs.edit().clear().apply();
        userName = null;
        userPhone = null;
        emergencyContactName = null;
        emergencyContactPhone = null;
    }
    
    public interface UserDataCallback {
        void onSuccess();
        void onError(String message);
    }
}
