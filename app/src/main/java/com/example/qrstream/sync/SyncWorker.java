package com.example.qrstream.sync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.qrstream.provider.TransferContract.Transfers;
import com.example.qrstream.util.CrashReporter;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Uploads history rows with {@code synced = 0} to Firestore and marks them synced.
 *
 * <p>Runs only with a network connection, retries with exponential backoff, and does nothing
 * when nobody is signed in ("Continue offline"): those rows simply stay local until the user
 * logs in. Scheduled after every finished transfer, on app start and after login.</p>
 */
public class SyncWorker extends Worker {

    /** Unique work name, so several triggers never run several uploads at once. */
    public static final String UNIQUE_WORK_NAME = "sync_transfers";

    private static final long BACKOFF_SECONDS = 30L;
    private static final long UPLOAD_TIMEOUT_SECONDS = 30L;
    private static final int MAX_ATTEMPTS = 8;

    /** One unsynced row, copied out of the cursor. */
    private static final class Row {
        long id;
        long createdAt;
        String direction;
        String sha256;
        final Map<String, Object> metadata = new HashMap<>();
    }

    /**
     * @param context application context
     * @param params  worker parameters
     */
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Schedules a sync run. Safe to call from any thread and as often as needed.
     *
     * @param context any context
     */
    public static void enqueue(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        } catch (RuntimeException e) {
            CrashReporter.record("Cannot schedule sync", e);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException e) {
            // Firebase is not configured (placeholder google-services.json).
            return Result.success();
        }
        if (user == null) {
            return Result.success();
        }
        ContentResolver resolver = getApplicationContext().getContentResolver();
        List<Row> rows = loadUnsyncedRows(resolver);
        if (rows.isEmpty()) {
            return Result.success();
        }
        FirestoreRepository repository;
        try {
            repository = new FirestoreRepository();
        } catch (RuntimeException e) {
            CrashReporter.record("Firestore unavailable", e);
            return retryOrFail();
        }
        for (Row row : rows) {
            try {
                Tasks.await(repository.uploadTransfer(user.getUid(),
                                FirestoreRepository.documentId(row.createdAt, row.direction, row.sha256),
                                row.metadata),
                        UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                CrashReporter.record("Upload failed", e);
                return retryOrFail();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.retry();
            }
            markSynced(resolver, row.id);
        }
        return Result.success();
    }

    private Result retryOrFail() {
        return getRunAttemptCount() >= MAX_ATTEMPTS ? Result.failure() : Result.retry();
    }

    private static List<Row> loadUnsyncedRows(ContentResolver resolver) {
        List<Row> rows = new ArrayList<>();
        try (Cursor c = resolver.query(Transfers.CONTENT_URI, null,
                Transfers.SYNCED + "=0", null, Transfers.CREATED_AT + " ASC")) {
            if (c == null) {
                return rows;
            }
            int idIdx = c.getColumnIndexOrThrow(Transfers._ID);
            int nameIdx = c.getColumnIndexOrThrow(Transfers.FILE_NAME);
            int sizeIdx = c.getColumnIndexOrThrow(Transfers.FILE_SIZE);
            int dirIdx = c.getColumnIndexOrThrow(Transfers.DIRECTION);
            int chunkIdx = c.getColumnIndexOrThrow(Transfers.CHUNK_COUNT);
            int recIdx = c.getColumnIndexOrThrow(Transfers.RECOVERED_COUNT);
            int statusIdx = c.getColumnIndexOrThrow(Transfers.STATUS);
            int shaIdx = c.getColumnIndexOrThrow(Transfers.SHA256);
            int createdIdx = c.getColumnIndexOrThrow(Transfers.CREATED_AT);
            while (c.moveToNext()) {
                Row row = new Row();
                row.id = c.getLong(idIdx);
                row.createdAt = c.getLong(createdIdx);
                row.direction = c.getString(dirIdx);
                row.sha256 = c.getString(shaIdx);
                // Metadata only: never the file contents and never the local path.
                row.metadata.put(Transfers.FILE_NAME, c.getString(nameIdx));
                row.metadata.put(Transfers.FILE_SIZE, c.getLong(sizeIdx));
                row.metadata.put(Transfers.DIRECTION, row.direction);
                row.metadata.put(Transfers.CHUNK_COUNT, c.getLong(chunkIdx));
                row.metadata.put(Transfers.RECOVERED_COUNT, c.getLong(recIdx));
                row.metadata.put(Transfers.STATUS, c.getString(statusIdx));
                row.metadata.put(Transfers.SHA256, row.sha256);
                row.metadata.put(Transfers.CREATED_AT, row.createdAt);
                rows.add(row);
            }
        }
        return rows;
    }

    private static void markSynced(ContentResolver resolver, long id) {
        ContentValues values = new ContentValues();
        values.put(Transfers.SYNCED, 1);
        resolver.update(Transfers.itemUri(id), values, null, null);
    }
}
