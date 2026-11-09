package com.example.qrstream.util;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Failure-proof access to Firebase Authentication. If Firebase is not configured (placeholder
 * google-services.json) these methods behave as "nobody is signed in" instead of crashing.
 */
public final class AuthHelper {

    private AuthHelper() {
    }

    /**
     * @return the signed-in user, or null when signed out, offline or Firebase is unavailable
     */
    public static FirebaseUser currentUser() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Signs out. Does nothing if Firebase is unavailable. */
    public static void signOut() {
        try {
            FirebaseAuth.getInstance().signOut();
        } catch (RuntimeException e) {
            CrashReporter.record("Sign-out failed", e);
        }
    }
}
