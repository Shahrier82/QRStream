package com.example.qrstream.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.example.qrstream.R;
import com.example.qrstream.service.TransferService;
import com.example.qrstream.service.TransferState;

/**
 * Receives the PROGRESS, COMPLETED and FAILED broadcasts of {@link TransferService}.
 *
 * <p>The broadcasts are local to this app: the service addresses them with
 * {@code Intent.setPackage(getPackageName())} and this receiver is registered
 * {@code RECEIVER_NOT_EXPORTED}, so no other app can send or read them. Activities register
 * it while they are visible. Completion and failure also show a toast.</p>
 */
public final class TransferStatusReceiver extends BroadcastReceiver {

    /** UI callbacks, all on the main thread. Any method may be a no-op. */
    public interface Listener {
        /**
         * @param state throttled progress snapshot (without the chunk grid)
         */
        void onProgress(TransferState state);

        /**
         * @param state final snapshot of a successful transfer
         */
        void onCompleted(TransferState state);

        /**
         * @param state   final snapshot of a failed or interrupted transfer
         * @param message reason to show to the user
         */
        void onFailed(TransferState state, String message);
    }

    private final Listener listener;
    private boolean registered;

    /**
     * @param listener UI callbacks, may be null if only the toast is wanted
     */
    public TransferStatusReceiver(Listener listener) {
        this.listener = listener;
    }

    /**
     * Registers for the three transfer actions. Calling it twice has no effect.
     *
     * @param context context that owns the registration (an Activity)
     */
    public void register(Context context) {
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(TransferService.ACTION_PROGRESS);
        filter.addAction(TransferService.ACTION_COMPLETED);
        filter.addAction(TransferService.ACTION_FAILED);
        ContextCompat.registerReceiver(context, this, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
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
            // Already unregistered, nothing to do.
        }
        registered = false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        TransferState state = IntentCompat.getParcelableExtra(
                intent, TransferService.EXTRA_STATE, TransferState.class);
        if (state == null) {
            state = TransferState.idle();
        }
        String message = intent.getStringExtra(TransferService.EXTRA_MESSAGE);
        switch (intent.getAction()) {
            case TransferService.ACTION_PROGRESS:
                if (listener != null) {
                    listener.onProgress(state);
                }
                break;
            case TransferService.ACTION_COMPLETED:
                Toast.makeText(context,
                        context.getString(R.string.toast_transfer_completed, safeName(context, state)),
                        Toast.LENGTH_SHORT).show();
                if (listener != null) {
                    listener.onCompleted(state);
                }
                break;
            case TransferService.ACTION_FAILED:
                String reason = message != null ? message : context.getString(R.string.error_unknown);
                Toast.makeText(context,
                        context.getString(R.string.toast_transfer_failed, reason),
                        Toast.LENGTH_LONG).show();
                if (listener != null) {
                    listener.onFailed(state, reason);
                }
                break;
            default:
                break;
        }
    }

    private static String safeName(Context context, TransferState state) {
        return state.fileName != null ? state.fileName : context.getString(R.string.unknown_file);
    }
}
