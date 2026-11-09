package com.example.qrstream.sync;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cloud Firestore access for the transfer history.
 *
 * <p>Layout: {@code users/{uid}/transfers/{docId}}. Only metadata is stored (name, size,
 * direction, counts, status, hash, time). File contents and local paths are never uploaded.
 * Document ids are derived from the row, so uploading the same row twice (for example after a
 * retry) overwrites the same document instead of creating a duplicate.</p>
 */
public final class FirestoreRepository {

    /** Top-level collection of per-user data. */
    public static final String COLLECTION_USERS = "users";
    /** Sub-collection that holds one document per transfer. */
    public static final String COLLECTION_TRANSFERS = "transfers";
    /** Field set by the server when the document is written. */
    public static final String FIELD_SYNCED_AT = "synced_at";

    private static final int HASH_PREFIX_LENGTH = 8;

    private final FirebaseFirestore firestore;

    /** Uses the default Firestore instance. */
    public FirestoreRepository() {
        this.firestore = FirebaseFirestore.getInstance();
    }

    /**
     * @param createdAt time the transfer was recorded, in milliseconds
     * @param direction SENT or RECEIVED
     * @param sha256    file hash, may be null
     * @return a stable, Firestore-safe document id
     */
    public static String documentId(long createdAt, String direction, String sha256) {
        String hash = sha256 == null ? "nohash"
                : sha256.substring(0, Math.min(HASH_PREFIX_LENGTH, sha256.length()));
        return String.format(Locale.ROOT, "%d_%s_%s", createdAt,
                direction == null ? "NA" : direction, hash);
    }

    /**
     * Uploads the metadata of one transfer.
     *
     * @param uid      Firebase user id that owns the data
     * @param docId    document id from {@link #documentId}
     * @param metadata the fields to store (without file contents or paths)
     * @return a task that completes when the server has accepted the write
     */
    public Task<Void> uploadTransfer(String uid, String docId, Map<String, Object> metadata) {
        Map<String, Object> data = new HashMap<>(metadata);
        data.put(FIELD_SYNCED_AT, FieldValue.serverTimestamp());
        return firestore.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_TRANSFERS).document(docId)
                .set(data);
    }
}
