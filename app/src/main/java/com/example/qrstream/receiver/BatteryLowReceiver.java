package com.example.qrstream.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/**
 * Listens for {@link Intent#ACTION_BATTERY_LOW} and tells its owner (the transfer service) to
 * pause the active transfer.
 *
 * <p>Registered dynamically by the service, because implicit system broadcasts are not
 * reliably delivered to manifest-declared receivers on modern Android.</p>
 */
public final class BatteryLowReceiver extends BroadcastReceiver {

    /** Called when the system reports a low battery. */
    public interface Callback {
        /** The battery just dropped below the system's low threshold. */
        void onBatteryLow();
    }

    private final Callback callback;
    private boolean registered;

    /**
     * @param callback receives the low-battery event on the main thread
     */
    public BatteryLowReceiver(Callback callback) {
        this.callback = callback;
    }

    /**
     * Registers this receiver. Calling it twice has no effect.
     *
     * @param context context that owns the registration
     */
    public void register(Context context) {
        if (registered) {
            return;
        }
        // ACTION_BATTERY_LOW is a protected system broadcast that only the system can send,
        // so a receiver that accepts system broadcasts is safe here.
        ContextCompat.registerReceiver(context, this,
                new IntentFilter(Intent.ACTION_BATTERY_LOW), ContextCompat.RECEIVER_EXPORTED);
        registered = true;
    }

    /**
     * Unregisters this receiver. Calling it when not registered has no effect.
     *
     * @param context the context used to register
     */
    public void unregister(Context context) {
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered by the system, nothing to do.
        }
        registered = false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BATTERY_LOW.equals(intent.getAction())) {
            callback.onBatteryLow();
        }
    }
}
