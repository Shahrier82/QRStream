package com.example.qrstream.provider;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;

import com.example.qrstream.provider.TransferContract.Transfers;

/**
 * Content provider for the local transfer history.
 *
 * <p>URIs: {@code content://com.example.qrstream.provider/transfers} (all rows) and
 * {@code .../transfers/#} (one row). Every write notifies the table URI, so a
 * {@link android.database.ContentObserver} on it (see {@code HistoryActivity}) refreshes
 * automatically. The provider is not exported.</p>
 */
public class TransferProvider extends ContentProvider {

    private static final int MATCH_TRANSFERS = 1;
    private static final int MATCH_TRANSFER_ID = 2;

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(TransferContract.AUTHORITY, Transfers.TABLE_NAME, MATCH_TRANSFERS);
        MATCHER.addURI(TransferContract.AUTHORITY, Transfers.TABLE_NAME + "/#", MATCH_TRANSFER_ID);
    }

    private TransferDbHelper dbHelper;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        dbHelper = new TransferDbHelper(context);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
        builder.setTables(Transfers.TABLE_NAME);
        switch (MATCHER.match(uri)) {
            case MATCH_TRANSFERS:
                break;
            case MATCH_TRANSFER_ID:
                builder.appendWhere(Transfers._ID + "=" + ContentUris.parseId(uri));
                break;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
        String order = (sortOrder == null || sortOrder.isEmpty())
                ? Transfers.DEFAULT_SORT_ORDER : sortOrder;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = builder.query(db, projection, selection, selectionArgs, null, null, order);
        ContentResolver resolver = requireResolver();
        cursor.setNotificationUri(resolver, Transfers.CONTENT_URI);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        switch (MATCHER.match(uri)) {
            case MATCH_TRANSFERS:
                return Transfers.CONTENT_TYPE;
            case MATCH_TRANSFER_ID:
                return Transfers.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        if (MATCHER.match(uri) != MATCH_TRANSFERS) {
            throw new IllegalArgumentException("Insert is only supported on the table URI: " + uri);
        }
        if (initialValues == null) {
            throw new IllegalArgumentException("Values must not be null");
        }
        ContentValues values = new ContentValues(initialValues);
        String name = values.getAsString(Transfers.FILE_NAME);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("file_name is required");
        }
        if (!Transfers.isValidDirection(values.getAsString(Transfers.DIRECTION))) {
            throw new IllegalArgumentException("direction must be SENT or RECEIVED");
        }
        if (!Transfers.isValidStatus(values.getAsString(Transfers.STATUS))) {
            throw new IllegalArgumentException("status must be COMPLETED, FAILED or INTERRUPTED");
        }
        if (!values.containsKey(Transfers.CREATED_AT)) {
            values.put(Transfers.CREATED_AT, System.currentTimeMillis());
        }
        if (!values.containsKey(Transfers.SYNCED)) {
            values.put(Transfers.SYNCED, 0);
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long id = db.insertOrThrow(Transfers.TABLE_NAME, null, values);
        notifyChange();
        return ContentUris.withAppendedId(Transfers.CONTENT_URI, id);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (values == null || values.size() == 0) {
            return 0;
        }
        validateUpdate(values);
        String where = scopedSelection(uri, selection);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int count = db.update(Transfers.TABLE_NAME, values, where, selectionArgs);
        if (count > 0) {
            notifyChange();
        }
        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        String where = scopedSelection(uri, selection);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int count = db.delete(Transfers.TABLE_NAME, where, selectionArgs);
        if (count > 0) {
            notifyChange();
        }
        return count;
    }

    private static void validateUpdate(ContentValues values) {
        if (values.containsKey(Transfers.STATUS)
                && !Transfers.isValidStatus(values.getAsString(Transfers.STATUS))) {
            throw new IllegalArgumentException("Invalid status");
        }
        if (values.containsKey(Transfers.DIRECTION)
                && !Transfers.isValidDirection(values.getAsString(Transfers.DIRECTION))) {
            throw new IllegalArgumentException("Invalid direction");
        }
        if (values.containsKey(Transfers._ID)) {
            throw new IllegalArgumentException("_id cannot be updated");
        }
    }

    /** Adds the "_id = n" restriction of an item URI to the caller's selection. */
    private static String scopedSelection(Uri uri, String selection) {
        switch (MATCHER.match(uri)) {
            case MATCH_TRANSFERS:
                return selection;
            case MATCH_TRANSFER_ID:
                return DatabaseUtils.concatenateWhere(
                        Transfers._ID + "=" + ContentUris.parseId(uri), selection);
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    private void notifyChange() {
        requireResolver().notifyChange(Transfers.CONTENT_URI, null);
    }

    private ContentResolver requireResolver() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider is not attached to a context");
        }
        return context.getContentResolver();
    }
}
