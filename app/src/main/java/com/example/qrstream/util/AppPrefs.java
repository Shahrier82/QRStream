package com.example.qrstream.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.qrstream.protocol.ProtocolConstants;

/**
 * Small settings store: offline-mode flag, demo "simulate loss" switch and the last-used send
 * parameters. Reads and writes are tiny, so they are safe on the main thread.
 */
public final class AppPrefs {

    private static final String FILE = "qrstream_prefs";
    private static final String KEY_OFFLINE = "offline_mode";
    private static final String KEY_SIMULATE_LOSS = "simulate_loss";
    private static final String KEY_LOSS_PERCENT = "loss_percent";
    private static final String KEY_CHUNK_SIZE = "chunk_size";
    private static final String KEY_GROUP_SIZE = "group_size";
    private static final String KEY_FPS = "fps";
    private static final String KEY_STRONG_EC = "strong_ec";

    /** Default simulated loss in percent. */
    public static final int DEFAULT_LOSS_PERCENT = 10;
    /** Highest simulated loss the slider offers, in percent. */
    public static final int MAX_LOSS_PERCENT = 50;

    private final SharedPreferences prefs;

    /**
     * @param context any context
     */
    public AppPrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** @return true if the user chose "Continue offline" */
    public boolean isOfflineMode() {
        return prefs.getBoolean(KEY_OFFLINE, false);
    }

    /** @param offline whether to skip login on the next start */
    public void setOfflineMode(boolean offline) {
        prefs.edit().putBoolean(KEY_OFFLINE, offline).apply();
    }

    /** @return true if the Send screen should drop frames on purpose */
    public boolean isSimulateLoss() {
        return prefs.getBoolean(KEY_SIMULATE_LOSS, false);
    }

    /** @param enabled whether to simulate frame loss */
    public void setSimulateLoss(boolean enabled) {
        prefs.edit().putBoolean(KEY_SIMULATE_LOSS, enabled).apply();
    }

    /** @return simulated loss in percent, 0 to {@link #MAX_LOSS_PERCENT} */
    public int getLossPercent() {
        return clamp(prefs.getInt(KEY_LOSS_PERCENT, DEFAULT_LOSS_PERCENT), 0, MAX_LOSS_PERCENT);
    }

    /** @param percent simulated loss in percent */
    public void setLossPercent(int percent) {
        prefs.edit().putInt(KEY_LOSS_PERCENT, clamp(percent, 0, MAX_LOSS_PERCENT)).apply();
    }

    /** @return last chunk size in bytes */
    public int getChunkSize() {
        return clamp(prefs.getInt(KEY_CHUNK_SIZE, ProtocolConstants.DEFAULT_CHUNK_SIZE),
                ProtocolConstants.MIN_CHUNK_SIZE, ProtocolConstants.MAX_CHUNK_SIZE);
    }

    /** @param size chunk size in bytes */
    public void setChunkSize(int size) {
        prefs.edit().putInt(KEY_CHUNK_SIZE, size).apply();
    }

    /** @return last parity group size, 0 means no parity */
    public int getGroupSize() {
        return clamp(prefs.getInt(KEY_GROUP_SIZE, ProtocolConstants.DEFAULT_GROUP_SIZE),
                ProtocolConstants.NO_PARITY, ProtocolConstants.MAX_GROUP_SIZE);
    }

    /** @param size group size, 0 for no parity */
    public void setGroupSize(int size) {
        prefs.edit().putInt(KEY_GROUP_SIZE, size).apply();
    }

    /** @return last frame rate */
    public int getFps() {
        return clamp(prefs.getInt(KEY_FPS, ProtocolConstants.DEFAULT_FPS),
                ProtocolConstants.MIN_FPS, ProtocolConstants.MAX_FPS);
    }

    /** @param fps frames per second */
    public void setFps(int fps) {
        prefs.edit().putInt(KEY_FPS, fps).apply();
    }

    /** @return true for QR error correction level M, false for L */
    public boolean isStrongErrorCorrection() {
        return prefs.getBoolean(KEY_STRONG_EC, false);
    }

    /** @param strong true for level M, false for level L */
    public void setStrongErrorCorrection(boolean strong) {
        prefs.edit().putBoolean(KEY_STRONG_EC, strong).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
