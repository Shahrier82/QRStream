package com.example.qrstream.util;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.example.qrstream.protocol.HashUtil;
import com.example.qrstream.provider.TransferContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Storage of received files in the app's private {@code files/received} folder, the only
 * folder the FileProvider exposes.
 *
 * <p>All methods do blocking file I/O. Call them from a background thread.</p>
 */
public final class FileStore {

    private static final String RECEIVED_DIR = "received";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_UNIQUE_ATTEMPTS = 1000;

    private FileStore() {
    }

    /**
     * @param context any context
     * @return the folder that holds received files (not guaranteed to exist yet)
     */
    public static File receivedDir(Context context) {
        return new File(context.getFilesDir(), RECEIVED_DIR);
    }

    /**
     * Writes bytes to a new file in the received folder. An existing file is never overwritten:
     * a numeric suffix is added to the name instead ("a.txt", "a (1).txt", ...).
     *
     * @param context  any context
     * @param fileName a name that has already been sanitized
     * @param data     file content
     * @return the file that was written
     * @throws IOException if the folder or file cannot be created or written
     */
    public static File saveReceived(Context context, String fileName, byte[] data)
            throws IOException {
        File dir = receivedDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create folder " + dir);
        }
        File target = uniqueFile(dir, fileName);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            // Do not leave a half-written file behind.
            if (target.exists() && !target.delete()) {
                target.deleteOnExit();
            }
            throw e;
        }
        return target;
    }

    private static File uniqueFile(File dir, String fileName) throws IOException {
        File candidate = new File(dir, fileName);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i <= MAX_UNIQUE_ATTEMPTS; i++) {
            candidate = new File(dir, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        throw new IOException("Too many files named " + fileName);
    }

    /**
     * Reads a whole file. Received files are at most 200 KB.
     *
     * @param file file to read
     * @return its content
     * @throws IOException if it cannot be read
     */
    public static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * @param file file to hash
     * @return lowercase hex SHA-256 of its content
     * @throws IOException if it cannot be read
     */
    public static String sha256Hex(File file) throws IOException {
        return HashUtil.sha256Hex(readAll(file));
    }

    /**
     * @param context any context
     * @param path    a path stored in the database, may be null
     * @return true if the path points to a regular file inside the received folder
     */
    public static boolean isStoredFile(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            File file = new File(path).getCanonicalFile();
            File dir = receivedDir(context).getCanonicalFile();
            return file.isFile() && dir.equals(file.getParentFile());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deletes a stored file. Paths outside the received folder are refused, so a corrupted
     * database row can never make the app delete something else.
     *
     * @param context any context
     * @param path    path stored in the database, may be null
     * @return true if a file was deleted
     */
    public static boolean deleteStored(Context context, String path) {
        if (!isStoredFile(context, path)) {
            return false;
        }
        return new File(path).delete();
    }

    /**
     * @param context any context
     * @param file    a file inside the received folder
     * @return a content:// URI served by the FileProvider
     * @throws IllegalArgumentException if the file is outside the shared folders
     */
    public static Uri shareUri(Context context, File file) {
        return FileProvider.getUriForFile(context, TransferContract.FILE_AUTHORITY, file);
    }
}
