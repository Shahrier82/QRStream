package com.example.qrstream.ui;

import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivityHistoryBinding;
import com.example.qrstream.provider.TransferContract.Transfers;
import com.example.qrstream.util.CrashReporter;
import com.example.qrstream.util.FileStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * History screen: a {@link android.widget.ListView} with a CursorAdapter over the transfer
 * content provider.
 *
 * <p>Tap opens the details, long-press asks for confirmation and deletes the record and its
 * file. A {@link ContentObserver} on the table URI reloads the list whenever any component
 * changes it (for example a transfer finishing in the service). All queries run off the main
 * thread and go through the {@code ContentResolver}.</p>
 */
public class HistoryActivity extends AppCompatActivity {

    private static final String STATE_FILTER = "filter";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_SENT = 1;
    private static final int FILTER_RECEIVED = 2;

    private ActivityHistoryBinding binding;
    private TransferCursorAdapter adapter;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private int filter = FILTER_ALL;

    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (savedInstanceState != null) {
            filter = savedInstanceState.getInt(STATE_FILTER, FILTER_ALL);
        }

        adapter = new TransferCursorAdapter(this, null);
        binding.listTransfers.setAdapter(adapter);
        binding.listTransfers.setEmptyView(binding.emptyView);
        binding.listTransfers.setOnItemClickListener((parent, view, position, id) -> openDetails(id));
        binding.listTransfers.setOnItemLongClickListener(this::confirmDelete);

        binding.chipGroupFilter.check(filter == FILTER_SENT ? R.id.chip_sent
                : filter == FILTER_RECEIVED ? R.id.chip_received : R.id.chip_all);
        binding.chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            int checked = checkedIds.get(0);
            filter = checked == R.id.chip_sent ? FILTER_SENT
                    : checked == R.id.chip_received ? FILTER_RECEIVED : FILTER_ALL;
            reload();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(Transfers.CONTENT_URI, true, observer);
        reload();
    }

    @Override
    protected void onStop() {
        getContentResolver().unregisterContentObserver(observer);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        loadGeneration.incrementAndGet();
        ioExecutor.shutdown();
        adapter.changeCursor(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_FILTER, filter);
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        final int generation = loadGeneration.incrementAndGet();
        final String selection;
        final String[] args;
        if (filter == FILTER_ALL) {
            selection = null;
            args = null;
        } else {
            selection = Transfers.DIRECTION + "=?";
            args = new String[]{filter == FILTER_SENT
                    ? Transfers.DIRECTION_SENT : Transfers.DIRECTION_RECEIVED};
        }
        ioExecutor.execute(() -> {
            Cursor cursor;
            try {
                cursor = getContentResolver().query(Transfers.CONTENT_URI, null, selection, args,
                        Transfers.DEFAULT_SORT_ORDER);
                if (cursor != null) {
                    // Fills the cursor window here, on the background thread.
                    cursor.getCount();
                }
            } catch (RuntimeException e) {
                CrashReporter.record("History query failed", e);
                return;
            }
            final Cursor loaded = cursor;
            runOnUiThread(() -> {
                if (loaded == null) {
                    return;
                }
                if (isDestroyed() || generation != loadGeneration.get()) {
                    loaded.close();
                    return;
                }
                adapter.changeCursor(loaded);
            });
        });
    }

    // ------------------------------------------------------------------ actions

    private void openDetails(long id) {
        Intent intent = new Intent(this, DetailsActivity.class);
        intent.setData(Transfers.itemUri(id));
        startActivity(intent);
    }

    private boolean confirmDelete(AdapterView<?> parent, android.view.View view, int position, long id) {
        Cursor cursor = (Cursor) adapter.getItem(position);
        if (cursor == null) {
            return false;
        }
        final String name = cursor.getString(cursor.getColumnIndexOrThrow(Transfers.FILE_NAME));
        final String path = cursor.getString(cursor.getColumnIndexOrThrow(Transfers.LOCAL_PATH));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_delete_title)
                .setMessage(getString(R.string.history_delete_message, name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteRecord(id, path))
                .show();
        return true;
    }

    private void deleteRecord(long id, String path) {
        ioExecutor.execute(() -> {
            try {
                FileStore.deleteStored(getApplicationContext(), path);
                getContentResolver().delete(Transfers.itemUri(id), null, null);
            } catch (RuntimeException e) {
                CrashReporter.record("Delete failed", e);
            }
            // The ContentObserver reloads the list.
        });
    }
}
