package com.example.qrstream.util;

import android.util.Log;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Thin, failure-proof wrapper around Firebase Crashlytics.
 *
 * <p>Crashlytics may be unusable (placeholder google-services.json, no Play services). Every
 * method swallows its own errors so reporting can never crash the app.</p>
 */
public final class CrashReporter {

    private static final String TAG = "QRStream";

    private CrashReporter() {
    }

    /**
     * Adds a breadcrumb to the next crash report.
     *
     * @param message short text
     */
    public static void log(String message) {
        Log.d(TAG, message);
        try {
            FirebaseCrashlytics.getInstance().log(message);
        } catch (RuntimeException e) {
            Log.w(TAG, "Crashlytics log failed", e);
        }
    }

    /**
     * Records a handled exception, for example a barcode decode failure.
     *
     * @param context short description of what was being done
     * @param error   the problem
     */
    public static void record(String context, Throwable error) {
        Log.w(TAG, context, error);
        try {
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.log(context);
            crashlytics.recordException(error);
        } catch (RuntimeException e) {
            Log.w(TAG, "Crashlytics record failed", e);
        }
    }
}
