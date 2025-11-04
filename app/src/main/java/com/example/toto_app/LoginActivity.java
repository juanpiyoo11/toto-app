package com.example.toto_app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.toto_app.network.APIService;
import com.example.toto_app.network.LoginRequest;
import com.example.toto_app.network.LoginResponse;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.TokenLoginRequest;
import com.example.toto_app.services.FallDetectionService;
import com.example.toto_app.services.WakeWordService;
import com.example.toto_app.util.TokenManager;

import ar.edu.uade.toto_app.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private EditText etToken;
    private Button btnLogin;
    private ProgressBar progressBar;
    private TextView tvError;
    private TokenManager tokenManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tokenManager = new TokenManager(this);
        RetrofitClient.init(this);

        if (tokenManager.getAccessToken() != null && !tokenManager.getAccessToken().isEmpty()) {
            Log.d(TAG, "User already logged in, redirecting to MainActivity");
            navigateToMain();
            return;
        }

        stopService(new Intent(this, WakeWordService.class));
        stopService(new Intent(this, FallDetectionService.class));
        Log.d(TAG, "Stopped all services - user not authenticated");

        setContentView(R.layout.activity_login);

        etToken = findViewById(R.id.etToken);
        btnLogin = findViewById(R.id.btnLogin);
        progressBar = findViewById(R.id.progressBar);
        tvError = findViewById(R.id.tvError);

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String token = etToken.getText() != null ? etToken.getText().toString().trim() : "";

        if (token.isEmpty()) {
            tvError.setText("Por favor ingresa tu código");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        if (token.length() != 6) {
            tvError.setText("El código debe tener 6 dígitos");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        tvError.setVisibility(View.GONE);
        setLoading(true);

        TokenLoginRequest request = new TokenLoginRequest(token);
        APIService api = RetrofitClient.api();

        api.loginWithToken(request).enqueue(new Callback<LoginResponse>() {
            @Override
            public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                setLoading(false);

                if (response.isSuccessful() && response.body() != null) {
                    LoginResponse loginResponse = response.body();

                    tokenManager.saveTokens(
                            loginResponse.getAccessToken(),
                            loginResponse.getRefreshToken()
                    );

                    Log.d(TAG, "Login successful, user: " +
                            (loginResponse.getUser() != null ? loginResponse.getUser().getName() : "unknown"));

                    Toast.makeText(LoginActivity.this, "¡Bienvenido!", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else {
                    String errorMsg = "Código inválido";
                    if (response.code() == 401) {
                        errorMsg = "Código incorrecto";
                    } else if (response.code() >= 500) {
                        errorMsg = "Error del servidor. Intenta más tarde.";
                    }

                    Log.w(TAG, "Login failed: " + response.code());
                    tvError.setText(errorMsg);
                    tvError.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onFailure(Call<LoginResponse> call, Throwable t) {
                setLoading(false);
                Log.e(TAG, "Login network error", t);

                tvError.setText("Error de conexión. Verifica tu internet.");
                tvError.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        etToken.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
