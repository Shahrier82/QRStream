package com.example.qrstream.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivityDetailsBinding;
import com.example.qrstream.provider.TransferContract.Transfers;
import com.example.qrstream.util.CrashReporter;
import com.example.qrstream.util.FileStore;
import com.example.qrstream.util.Formatters;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Details of one history row, with Open, Share and Re-verify hash actions. */
public class DetailsActivity extends AppCompatActivity {

    private static final String FALLBACK_MIME = "application/octet-stream";

    private ActivityDetailsBinding binding;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private String fileName;
    private String storedHash;
    private File storedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDetailsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setFileActionsVisible(false);

        binding.buttonOpen.setOnClickListener(v -> openFile());
        binding.buttonShare.setOnClickListener(v -> shareFile());
        binding.buttonVerify.setOnClickListener(v -> verifyHash());

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        load(uri);
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdown();
        super.onDestroy();
    }

    private void load(Uri uri) {
        ioExecutor.execute(() -> {
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c == null || !c.moveToFirst()) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.details_not_found, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
                final String name = c.getString(c.getColumnIndexOrThrow(Transfers.FILE_NAME));
                final long size = c.getLong(c.getColumnIndexOrThrow(Transfers.FILE_SIZE));
                final String direction = c.getString(c.getColumnIndexOrThrow(Transfers.DIRECTION));
                final int chunks = c.getInt(c.getColumnIndexOrThrow(Transfers.CHUNK_COUNT));
                final int recovered = c.getInt(c.getColumnIndexOrThrow(Transfers.RECOVERED_COUNT));
                final String status = c.getString(c.getColumnIndexOrThrow(Transfers.STATUS));
                final String sha = c.getString(c.getColumnIndexOrThrow(Transfers.SHA256));
                final String path = c.getString(c.getColumnIndexOrThrow(Transfers.LOCAL_PATH));
                final long created = c.getLong(c.getColumnIndexOrThrow(Transfers.CREATED_AT));
                final boolean synced = c.getInt(c.getColumnIndexOrThrow(Transfers.SYNCED)) == 1;
                final boolean fileExists = FileStore.isStoredFile(getApplicationContext(), path);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    fileName = name;
                    storedHash = sha;
                    storedFile = fileExists ? new File(path) : null;
                    show(name, size, direction, chunks, recovered, status, sha, path, created, synced);
                });
            } catch (RuntimeException e) {
                CrashReporter.record("Details query failed", e);
                runOnUiThread(this::finish);
            }
        });
    }

    private void show(String name, long size, String direction, int chunks, int recovered,
                      String status, String sha, String path, long created, boolean synced) {
        binding.textName.setText(name);
        binding.valueDirection.setText(Transfers.DIRECTION_SENT.equals(direction)
                ? R.string.direction_sent : R.string.direction_received);
        binding.valueStatus.setText(statusLabel(status));
        binding.valueSize.setText(getString(R.string.details_size_value,
                Formatters.formatSize(size), size));
        binding.valueChunks.setText(String.valueOf(chunks));
        binding.valueRecovered.setText(String.valueOf(recovered));
        binding.valueHash.setText(sha == null ? "-" : sha);
        binding.valuePath.setText(path == null ? getString(R.string.details_no_copy) : path);
        binding.valueDate.setText(Formatters.formatDate(created));
        binding.valueSynced.setText(synced ? R.string.details_synced_yes : R.string.details_synced_no);
        setFileActionsVisible(storedFile != null);
        binding.textNoFile.setVisibility(storedFile == null ? View.VISIBLE : View.GONE);
    }

    private int statusLabel(String status) {
        if (Transfers.STATUS_COMPLETED.equals(status)) {
            return R.string.status_completed;
        }
        if (Transfers.STATUS_FAILED.equals(status)) {
            return R.string.status_failed;
        }
        return R.string.status_interrupted;
    }

    private void setFileActionsVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        binding.buttonOpen.setVisibility(v);
        binding.buttonShare.setVisibility(v);
        binding.buttonVerify.setVisibility(v);
    }

    private String mimeType() {
        String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(Formatters.extensionOf(fileName));
        return mime == null ? FALLBACK_MIME : mime;
    }

    private void openFile() {
        if (storedFile == null) {
            return;
        }
        Uri uri = FileStore.shareUri(this, storedFile);
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("", uri));
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.details_no_app, Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile() {
        if (storedFile == null) {
            return;
        }
        Uri uri = FileStore.shareUri(this, storedFile);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mimeType())
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("", uri));
        startActivity(Intent.createChooser(send, getString(R.string.action_share)));
    }

    private void verifyHash() {
        if (storedFile == null || storedHash == null) {
            return;
        }
        binding.textVerifyResult.setVisibility(View.VISIBLE);
        binding.textVerifyResult.setText(R.string.details_verifying);
        final File file = storedFile;
        final String expected = storedHash;
        ioExecutor.execute(() -> {
            int message;
            try {
                message = FileStore.sha256Hex(file).equalsIgnoreCase(expected)
                        ? R.string.details_verify_ok : R.string.details_verify_bad;
            } catch (IOException e) {
                CrashReporter.record("Re-verify failed", e);
                message = R.string.details_verify_error;
            }
            final int result = message;
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    binding.textVerifyResult.setText(result);
                }
            });
        });
    }
}
