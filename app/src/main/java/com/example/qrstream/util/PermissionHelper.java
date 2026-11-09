package com.example.qrstream.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Runtime-permission checks and the shortcut to the app's system settings page. */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /**
     * @param context any context
     * @return true if the camera permission is granted
     */
    public static boolean hasCameraPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param context any context
     * @return true if notifications may be posted (always true before Android 13)
     */
    public static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true on Android versions that need the POST_NOTIFICATIONS runtime prompt
     */
    public static boolean needsNotificationPrompt() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * @param activity the activity asking
     * @return true if Android recommends showing an explanation before asking for the camera
     */
    public static boolean shouldExplainCamera(Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.CAMERA);
    }

    /**
     * @param context any context
     * @return an intent that opens this app's page in the system settings
     */
    public static Intent appSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", context.getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
