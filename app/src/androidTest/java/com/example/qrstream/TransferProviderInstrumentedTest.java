package com.example.qrstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.qrstream.provider.TransferContract.Transfers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented test of {@code TransferProvider}: insert, query, update, delete, MIME types and
 * input validation, always through the ContentResolver as the app does.
 *
 * <p>Run on a device or emulator with {@code ./gradlew connectedDebugAndroidTest}. The test
 * removes the rows it creates; it uses the file name prefix "provider-test-".</p>
 */
@RunWith(AndroidJUnit4.class)
public class TransferProviderInstrumentedTest {

    private static final String PREFIX = "provider-test-";
    private static final String HASH =
            "abababababababababababababababababababababababababababababababab";

    private ContentResolver resolver;

    @Before
    public void setUp() {
        resolver = InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
        cleanUp();
    }

    @After
    public void cleanUp() {
        resolver.delete(Transfers.CONTENT_URI, Transfers.FILE_NAME + " LIKE ?",
                new String[]{PREFIX + "%"});
    }

    private Uri insertSample(String name, String direction, String status) {
        return resolver.insert(Transfers.CONTENT_URI, Transfers.buildValues(
                PREFIX + name, 1234, direction, 4, 1, status, HASH, null));
    }

    @Test
    public void insertThenQueryItem() {
        Uri uri = insertSample("a.txt", Transfers.DIRECTION_RECEIVED, Transfers.STATUS_COMPLETED);
        assertNotNull(uri);
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            assertNotNull(c);
            assertTrue(c.moveToFirst());
            assertEquals(PREFIX + "a.txt", c.getString(c.getColumnIndexOrThrow(Transfers.FILE_NAME)));
            assertEquals(1234, c.getLong(c.getColumnIndexOrThrow(Transfers.FILE_SIZE)));
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow(Transfers.SYNCED)));
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow(Transfers.RECOVERED_COUNT)));
        }
    }

    @Test
    public void listIsNewestFirstAndFilterable() {
        ContentValues older = Transfers.buildValues(PREFIX + "old", 1, Transfers.DIRECTION_SENT,
                1, 0, Transfers.STATUS_COMPLETED, null, null);
        older.put(Transfers.CREATED_AT, 1000L);
        ContentValues newer = Transfers.buildValues(PREFIX + "new", 1, Transfers.DIRECTION_RECEIVED,
                1, 0, Transfers.STATUS_FAILED, null, null);
        newer.put(Transfers.CREATED_AT, 2000L);
        resolver.insert(Transfers.CONTENT_URI, older);
        resolver.insert(Transfers.CONTENT_URI, newer);

        try (Cursor c = resolver.query(Transfers.CONTENT_URI, null,
                Transfers.FILE_NAME + " LIKE ?", new String[]{PREFIX + "%"}, null)) {
            assertEquals(2, c.getCount());
            c.moveToFirst();
            assertEquals(PREFIX + "new", c.getString(c.getColumnIndexOrThrow(Transfers.FILE_NAME)));
        }
        try (Cursor c = resolver.query(Transfers.CONTENT_URI, null,
                Transfers.FILE_NAME + " LIKE ? AND " + Transfers.DIRECTION + "=?",
                new String[]{PREFIX + "%", Transfers.DIRECTION_SENT}, null)) {
            assertEquals(1, c.getCount());
        }
    }

    @Test
    public void updateMarksRowSynced() {
        Uri uri = insertSample("sync.txt", Transfers.DIRECTION_SENT, Transfers.STATUS_COMPLETED);
        ContentValues values = new ContentValues();
        values.put(Transfers.SYNCED, 1);
        assertEquals(1, resolver.update(uri, values, null, null));
        try (Cursor c = resolver.query(Transfers.CONTENT_URI, null,
                Transfers.SYNCED + "=0 AND " + Transfers.FILE_NAME + "=?",
                new String[]{PREFIX + "sync.txt"}, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void deleteRemovesTheRow() {
        Uri uri = insertSample("gone.txt", Transfers.DIRECTION_SENT, Transfers.STATUS_INTERRUPTED);
        assertEquals(1, resolver.delete(uri, null, null));
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void mimeTypesDistinguishDirAndItem() {
        Uri item = ContentUris.withAppendedId(Transfers.CONTENT_URI, 1);
        assertEquals(Transfers.CONTENT_TYPE, resolver.getType(Transfers.CONTENT_URI));
        assertEquals(Transfers.CONTENT_ITEM_TYPE, resolver.getType(item));
    }

    @Test
    public void invalidValuesAreRejected() {
        ContentValues badStatus = Transfers.buildValues(PREFIX + "x", 1, Transfers.DIRECTION_SENT,
                1, 0, "WHATEVER", null, null);
        try {
            resolver.insert(Transfers.CONTENT_URI, badStatus);
            fail("Invalid status was accepted");
        } catch (IllegalArgumentException expected) {
            // good
        }
        ContentValues badDirection = Transfers.buildValues(PREFIX + "y", 1, "SIDEWAYS",
                1, 0, Transfers.STATUS_COMPLETED, null, null);
        try {
            resolver.insert(Transfers.CONTENT_URI, badDirection);
            fail("Invalid direction was accepted");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }
}
