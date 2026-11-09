package com.example.qrstream.provider;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.qrstream.provider.TransferContract.Transfers;

/**
 * Creates and upgrades the SQLite database behind {@link TransferProvider}.
 *
 * <p>Only {@link TransferProvider} may use this class. Activities and services reach the data
 * through {@code ContentResolver}.</p>
 */
final class TransferDbHelper extends SQLiteOpenHelper {

    /** File name of the database. */
    static final String DATABASE_NAME = "transfers.db";
    /** Schema version. Bump it and extend {@link #onUpgrade} when the table changes. */
    static final int DATABASE_VERSION = 1;

    private static final String CREATE_TABLE =
            "CREATE TABLE " + Transfers.TABLE_NAME + " ("
                    + Transfers._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + Transfers.FILE_NAME + " TEXT NOT NULL, "
                    + Transfers.FILE_SIZE + " INTEGER NOT NULL DEFAULT 0, "
                    + Transfers.DIRECTION + " TEXT NOT NULL CHECK ("
                    + Transfers.DIRECTION + " IN ('SENT','RECEIVED')), "
                    + Transfers.CHUNK_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                    + Transfers.RECOVERED_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                    + Transfers.STATUS + " TEXT NOT NULL CHECK ("
                    + Transfers.STATUS + " IN ('COMPLETED','FAILED','INTERRUPTED')), "
                    + Transfers.SHA256 + " TEXT, "
                    + Transfers.LOCAL_PATH + " TEXT, "
                    + Transfers.CREATED_AT + " INTEGER NOT NULL, "
                    + Transfers.SYNCED + " INTEGER NOT NULL DEFAULT 0 CHECK ("
                    + Transfers.SYNCED + " IN (0,1)))";

    private static final String CREATE_INDEX_CREATED =
            "CREATE INDEX idx_transfers_created ON " + Transfers.TABLE_NAME
                    + " (" + Transfers.CREATED_AT + " DESC)";

    private static final String CREATE_INDEX_SYNCED =
            "CREATE INDEX idx_transfers_synced ON " + Transfers.TABLE_NAME
                    + " (" + Transfers.SYNCED + ")";

    /**
     * @param context any context, the application context is used
     */
    TransferDbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
        db.execSQL(CREATE_INDEX_CREATED);
        db.execSQL(CREATE_INDEX_SYNCED);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the only schema so far. The history is only a convenience log (the
        // files themselves are never stored here), so a future incompatible change may simply
        // rebuild the table.
        db.execSQL("DROP TABLE IF EXISTS " + Transfers.TABLE_NAME);
        onCreate(db);
    }
}
