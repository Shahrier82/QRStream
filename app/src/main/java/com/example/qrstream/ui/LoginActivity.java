package com.example.qrstream.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivityLoginBinding;
import com.example.qrstream.sync.SyncWorker;
import com.example.qrstream.util.AppPrefs;
import com.example.qrstream.util.AuthHelper;
import com.example.qrstream.util.CrashReporter;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;

/**
 * Login screen: email/password sign-in and registration with Firebase Authentication, plus
 * "Continue offline", which skips the account completely. Transfers never need an account.
 */
public class LoginActivity extends AppCompatActivity {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private ActivityLoginBinding binding;
    private AppPrefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new AppPrefs(this);
        if (prefs.isOfflineMode() || AuthHelper.currentUser() != null) {
            openHome();
            return;
        }
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.buttonLogin.setOnClickListener(v -> authenticate(false));
        binding.buttonRegister.setOnClickListener(v -> authenticate(true));
        binding.buttonOffline.setOnClickListener(v -> {
            prefs.setOfflineMode(true);
            openHome();
        });
    }

    private void authenticate(boolean register) {
        String email = text(binding.editEmail);
        String password = text(binding.editPassword);
        binding.layoutEmail.setError(null);
        binding.layoutPassword.setError(null);
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.layoutEmail.setError(getString(R.string.error_email_invalid));
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            binding.layoutPassword.setError(getString(R.string.error_password_short, MIN_PASSWORD_LENGTH));
            return;
        }
        FirebaseAuth auth;
        try {
            auth = FirebaseAuth.getInstance();
        } catch (RuntimeException e) {
            CrashReporter.record("Firebase Auth unavailable", e);
            showError(getString(R.string.error_firebase_not_configured));
            return;
        }
        setBusy(true);
        Task<AuthResult> task = register
                ? auth.createUserWithEmailAndPassword(email, password)
                : auth.signInWithEmailAndPassword(email, password);
        task.addOnCompleteListener(this, result -> {
            setBusy(false);
            if (result.isSuccessful()) {
                prefs.setOfflineMode(false);
                SyncWorker.enqueue(getApplicationContext());
                openHome();
            } else {
                showError(messageFor(result.getException()));
            }
        });
    }

    private String messageFor(Exception e) {
        if (e instanceof FirebaseNetworkException) {
            return getString(R.string.error_no_network);
        }
        if (e instanceof FirebaseAuthInvalidUserException
                || e instanceof FirebaseAuthInvalidCredentialsException) {
            return getString(R.string.error_bad_credentials);
        }
        if (e instanceof FirebaseAuthUserCollisionException) {
            return getString(R.string.error_account_exists);
        }
        if (e instanceof FirebaseAuthWeakPasswordException) {
            return getString(R.string.error_weak_password);
        }
        CrashReporter.record("Authentication failed", e == null ? new IllegalStateException("null") : e);
        return getString(R.string.error_auth_generic, e == null ? "" : e.getLocalizedMessage());
    }

    private void showError(String message) {
        binding.textError.setText(message);
        binding.textError.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.buttonLogin.setEnabled(!busy);
        binding.buttonRegister.setEnabled(!busy);
        binding.buttonOffline.setEnabled(!busy);
        binding.textError.setVisibility(View.GONE);
    }

    private static String text(com.google.android.material.textfield.TextInputEditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }
}
