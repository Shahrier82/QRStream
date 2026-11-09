package com.example.qrstream.util;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.example.qrstream.protocol.ProtocolConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the file chosen with the Storage Access Framework picker
 * ({@code ActivityResultContracts.OpenDocument}), so the app needs no storage permission.
 *
 * <p>All methods do blocking I/O. Call them from a background thread.</p>
 */
public final class FilePickerHelper {

    /** MIME filter passed to the picker: every file type. */
    public static final String[] PICKER_MIME_TYPES = {"*/*"};

    private static final int BUFFER_SIZE = 8192;
    private static final String FALLBACK_NAME = "file";

    private FilePickerHelper() {
    }

    /** A file read completely into memory. */
    public static final class PickedFile {
        private final String name;
        private final String mimeType;
        private final byte[] bytes;

        PickedFile(String name, String mimeType, byte[] bytes) {
            this.name = name;
            this.mimeType = mimeType;
            this.bytes = bytes;
        }

        /** @return display name reported by the document provider */
        public String getName() {
            return name;
        }

        /** @return MIME type, or application/octet-stream when unknown */
        public String getMimeType() {
            return mimeType;
        }

        /** @return the file content, not copied */
        public byte[] getBytes() {
            return bytes;
        }

        /** @return size in bytes */
        public int getSize() {
            return bytes.length;
        }
    }

    /** Thrown when the chosen file is larger than the protocol limit. */
    public static final class FileTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        /**
         * @param message explanation shown to the user
         */
        public FileTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Reads the document behind a URI.
     *
     * @param resolver content resolver
     * @param uri      URI returned by the picker
     * @return the file in memory
     * @throws FileTooLargeException if it exceeds 200 KB (checked before reading when the size
     *                               is known, and while reading otherwise)
     * @throws IOException           if the document cannot be opened or read
     */
    public static PickedFile read(ContentResolver resolver, Uri uri) throws IOException {
        String name = FALLBACK_NAME;
        long declaredSize = -1;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException e) {
            // Some providers refuse the query; fall back to reading the stream.
            declaredSize = -1;
        }
        if (declaredSize > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
            throw new FileTooLargeException("File is " + declaredSize + " bytes");
        }

        String mime = resolver.getType(uri);
        if (mime == null) {
            mime = ProtocolConstants.DEFAULT_MIME_TYPE;
        }

        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    declaredSize > 0 ? (int) declaredSize : BUFFER_SIZE);
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
                    throw new FileTooLargeException("File is larger than "
                            + ProtocolConstants.MAX_FILE_SIZE_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return new PickedFile(name, mime, out.toByteArray());
        }
    }
}
