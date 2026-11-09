package com.example.qrstream.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.BaseColumns;

/**
 * Public description of the transfer-history content provider: authority, URIs, column names
 * and the allowed values of the enumerated columns.
 */
public final class TransferContract {

    /** Authority of {@link TransferProvider}. */
    public static final String AUTHORITY = "com.example.qrstream.provider";
    /** Authority of the FileProvider that shares received files. */
    public static final String FILE_AUTHORITY = "com.example.qrstream.fileprovider";
    /** Root of every URI served by the provider. */
    public static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY);

    private TransferContract() {
    }

    /** The {@code transfers} table. */
    public static final class Transfers implements BaseColumns {

        /** Table and path name. */
        public static final String TABLE_NAME = "transfers";
        /** URI of the whole table (a "dir" URI). */
        public static final Uri CONTENT_URI = BASE_URI.buildUpon().appendPath(TABLE_NAME).build();
        /** MIME type of a list of rows. */
        public static final String CONTENT_TYPE =
                ContentResolver.CURSOR_DIR_BASE_TYPE + "/vnd." + AUTHORITY + "." + TABLE_NAME;
        /** MIME type of one row. */
        public static final String CONTENT_ITEM_TYPE =
                ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd." + AUTHORITY + "." + TABLE_NAME;

        /** TEXT: display name of the file. */
        public static final String FILE_NAME = "file_name";
        /** INTEGER: file size in bytes. */
        public static final String FILE_SIZE = "file_size";
        /** TEXT: {@link #DIRECTION_SENT} or {@link #DIRECTION_RECEIVED}. */
        public static final String DIRECTION = "direction";
        /** INTEGER: number of DATA chunks. */
        public static final String CHUNK_COUNT = "chunk_count";
        /** INTEGER: chunks rebuilt from parity (received transfers). */
        public static final String RECOVERED_COUNT = "recovered_count";
        /** TEXT: one of the STATUS_ constants. */
        public static final String STATUS = "status";
        /** TEXT: lowercase hex SHA-256 of the file. */
        public static final String SHA256 = "sha256";
        /** TEXT: absolute path of the stored copy (received transfers), may be null. */
        public static final String LOCAL_PATH = "local_path";
        /** INTEGER: creation time in milliseconds since the epoch. */
        public static final String CREATED_AT = "created_at";
        /** INTEGER: 1 once the row has been uploaded to Firestore, else 0. */
        public static final String SYNCED = "synced";

        /** Direction value for a file this device sent. */
        public static final String DIRECTION_SENT = "SENT";
        /** Direction value for a file this device received. */
        public static final String DIRECTION_RECEIVED = "RECEIVED";

        /** Status value for a finished transfer. */
        public static final String STATUS_COMPLETED = "COMPLETED";
        /** Status value for a failed transfer (for example a hash mismatch). */
        public static final String STATUS_FAILED = "FAILED";
        /** Status value for a transfer that was stopped before it finished. */
        public static final String STATUS_INTERRUPTED = "INTERRUPTED";

        /** Default sort order: newest first. */
        public static final String DEFAULT_SORT_ORDER = CREATED_AT + " DESC";

        private Transfers() {
        }

        /**
         * @param id row id
         * @return the URI of one row
         */
        public static Uri itemUri(long id) {
            return android.content.ContentUris.withAppendedId(CONTENT_URI, id);
        }

        /**
         * @param status a status value
         * @return true if the status is one of the three allowed values
         */
        public static boolean isValidStatus(String status) {
            return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status)
                    || STATUS_INTERRUPTED.equals(status);
        }

        /**
         * @param direction a direction value
         * @return true if the direction is SENT or RECEIVED
         */
        public static boolean isValidDirection(String direction) {
            return DIRECTION_SENT.equals(direction) || DIRECTION_RECEIVED.equals(direction);
        }

        /**
         * Builds the values for inserting a finished transfer.
         *
         * @param fileName       display name
         * @param fileSize       size in bytes
         * @param direction      SENT or RECEIVED
         * @param chunkCount     number of DATA chunks
         * @param recoveredCount chunks rebuilt from parity
         * @param status         COMPLETED, FAILED or INTERRUPTED
         * @param sha256         hex digest, may be null
         * @param localPath      stored copy, may be null
         * @return values ready for {@code ContentResolver.insert}; synced is 0
         */
        public static ContentValues buildValues(String fileName, long fileSize, String direction,
                                                int chunkCount, int recoveredCount, String status,
                                                String sha256, String localPath) {
            ContentValues values = new ContentValues();
            values.put(FILE_NAME, fileName);
            values.put(FILE_SIZE, fileSize);
            values.put(DIRECTION, direction);
            values.put(CHUNK_COUNT, chunkCount);
            values.put(RECOVERED_COUNT, recoveredCount);
            values.put(STATUS, status);
            values.put(SHA256, sha256);
            values.put(LOCAL_PATH, localPath);
            values.put(CREATED_AT, System.currentTimeMillis());
            values.put(SYNCED, 0);
            return values;
        }
    }
}
