package com.example.qrstream.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivityBenchmarkBinding;
import com.example.qrstream.protocol.BenchmarkRunner;
import com.example.qrstream.protocol.ProtocolConstants;
import com.example.qrstream.util.CrashReporter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app benchmark: compares no parity with K = 8, 4 and 2 at 0, 5, 10 and 20 percent loss over
 * 100 seeded trials each, and prints the table for the project report. The same code is run by
 * {@code BenchmarkTest} on the JVM.
 */
public class BenchmarkActivity extends AppCompatActivity {

    private static final String STATE_RESULT = "result";
    private static final int[] GROUP_SIZES = {ProtocolConstants.NO_PARITY, 8, 4, 2};
    private static final double[] LOSSES = {0.0, 0.05, 0.10, 0.20};

    private ActivityBenchmarkBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String result;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBenchmarkBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.textDescription.setText(getString(R.string.benchmark_description,
                BenchmarkRunner.DEFAULT_TRIALS, BenchmarkRunner.DEFAULT_FILE_SIZE / 1024,
                ProtocolConstants.DEFAULT_CHUNK_SIZE));
        if (savedInstanceState != null) {
            result = savedInstanceState.getString(STATE_RESULT);
        }
        binding.buttonRun.setOnClickListener(v -> run());
        binding.buttonCopy.setOnClickListener(v -> copy());
        render();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (result != null) {
            outState.putString(STATE_RESULT, result);
        }
    }

    private void run() {
        if (running) {
            return;
        }
        running = true;
        render();
        executor.execute(() -> {
            String text;
            try {
                List<BenchmarkRunner.Cell> cells = BenchmarkRunner.run(
                        BenchmarkRunner.DEFAULT_FILE_SIZE, ProtocolConstants.DEFAULT_CHUNK_SIZE,
                        GROUP_SIZES, LOSSES, BenchmarkRunner.DEFAULT_TRIALS);
                text = BenchmarkRunner.formatTables(cells, GROUP_SIZES, LOSSES,
                        BenchmarkRunner.DEFAULT_FILE_SIZE, ProtocolConstants.DEFAULT_CHUNK_SIZE);
            } catch (RuntimeException e) {
                CrashReporter.record("Benchmark failed", e);
                text = getString(R.string.error_unknown);
            }
            final String finalText = text;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                result = finalText;
                running = false;
                render();
            });
        });
    }

    private void render() {
        binding.progress.setVisibility(running ? View.VISIBLE : View.GONE);
        binding.buttonRun.setEnabled(!running);
        binding.buttonCopy.setEnabled(result != null);
        binding.textResult.setText(result == null ? "" : result);
    }

    private void copy() {
        if (result == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.title_benchmark), result));
        Toast.makeText(this, R.string.benchmark_copied, Toast.LENGTH_SHORT).show();
    }
}
