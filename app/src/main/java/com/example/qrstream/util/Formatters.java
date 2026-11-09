package com.example.qrstream.util;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Text formatting helpers for sizes, durations, speeds and file names.
 *
 * <p>Pure Java (no Android classes) so it can be unit-tested on the JVM.</p>
 */
public final class Formatters {

    private static final long KILO = 1024L;
    private static final long MEGA = KILO * 1024L;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final int MAX_SANITIZED_LENGTH = 100;
    private static final String FALLBACK_FILE_NAME = "received_file";

    private Formatters() {
    }

    /**
     * @param bytes size in bytes, negative values are shown as 0 B
     * @return for example "512 B", "1.5 KB" or "2.0 MB"
     */
    public static String formatSize(long bytes) {
        long value = Math.max(0, bytes);
        if (value < KILO) {
            return String.format(Locale.getDefault(), "%d B", value);
        }
        if (value < MEGA) {
            return String.format(Locale.getDefault(), "%.1f KB", value / (double) KILO);
        }
        return String.format(Locale.getDefault(), "%.1f MB", value / (double) MEGA);
    }

    /**
     * @param millis duration in milliseconds
     * @return for example "850 ms", "12.3 s" or "2 min 05 s"
     */
    public static String formatDuration(long millis) {
        long ms = Math.max(0, millis);
        if (ms < MILLIS_PER_SECOND) {
            return String.format(Locale.getDefault(), "%d ms", ms);
        }
        long totalSeconds = ms / MILLIS_PER_SECOND;
        if (totalSeconds < SECONDS_PER_MINUTE) {
            return String.format(Locale.getDefault(), "%.1f s", ms / (double) MILLIS_PER_SECOND);
        }
        return String.format(Locale.getDefault(), "%d min %02d s",
                totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE);
    }

    /**
     * @param bytesPerSecond throughput
     * @return for example "850 B/s" or "1.2 KB/s"
     */
    public static String formatSpeed(double bytesPerSecond) {
        if (Double.isNaN(bytesPerSecond) || bytesPerSecond < 0) {
            return "0 B/s";
        }
        return formatSize(Math.round(bytesPerSecond)) + "/s";
    }

    /**
     * @param percent value from 0 to 100
     * @return for example "37%" or "12.5%"
     */
    public static String formatPercent(double percent) {
        if (percent == Math.rint(percent)) {
            return String.format(Locale.getDefault(), "%d%%", (long) percent);
        }
        return String.format(Locale.getDefault(), "%.1f%%", percent);
    }

    /**
     * @param millis time since the epoch
     * @return a short localized date and time
     */
    public static String formatDate(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(millis));
    }

    /**
     * Shortens a long hash for display.
     *
     * @param hex full hex string, may be null
     * @return the first 8 and last 8 characters joined by "...", or the input if short
     */
    public static String abbreviateHash(String hex) {
        if (hex == null || hex.isEmpty()) {
            return "-";
        }
        final int edge = 8;
        if (hex.length() <= edge * 2 + 3) {
            return hex;
        }
        return hex.substring(0, edge) + "..." + hex.substring(hex.length() - edge);
    }

    /**
     * Turns a name received from another device into a safe local file name. Untrusted input:
     * the sender controls the text, so path parts, control characters and leading dots are
     * removed.
     *
     * @param name name from the META frame, may be null
     * @return a non-empty name of at most 100 characters without path separators
     */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return FALLBACK_FILE_NAME;
        }
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' '
                    || c == '(' || c == ')') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String cleaned = sb.toString().trim();
        int start = 0;
        while (start < cleaned.length() && cleaned.charAt(start) == '.') {
            start++;
        }
        cleaned = cleaned.substring(start);
        if (cleaned.length() > MAX_SANITIZED_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            String ext = (dot > 0 && cleaned.length() - dot <= 16) ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, MAX_SANITIZED_LENGTH - ext.length()) + ext;
        }
        return cleaned.isEmpty() ? FALLBACK_FILE_NAME : cleaned;
    }

    /**
     * @param fileName a file name
     * @return the lowercase extension without the dot, or an empty string
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
