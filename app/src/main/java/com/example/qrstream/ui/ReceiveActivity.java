package com.example.qrstream.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivityReceiveBinding;
import com.example.qrstream.receiver.TransferStatusReceiver;
import com.example.qrstream.service.TransferBinder;
import com.example.qrstream.service.TransferService;
import com.example.qrstream.service.TransferState;
import com.example.qrstream.util.CrashReporter;
import com.example.qrstream.util.Formatters;
import com.example.qrstream.util.PermissionHelper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import android.util.Size;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receive screen: camera preview, chunk grid and live statistics.
 *
 * <p>The camera stays here. Every decoded QR text is handed to {@link TransferService}, which
 * owns the {@code Reassembler}. Decoding runs on a dedicated analyzer thread, never on the main
 * thread.</p>
 */
public class ReceiveActivity extends AppCompatActivity {

    private static final String STATE_FINISHED = "finished_state";
    private static final int ANALYSIS_WIDTH = 1920;
    private static final int ANALYSIS_HEIGHT = 1080;
    private static final int LOG_FIRST_FAILURES = 5;
    private static final int LOG_EVERY_NTH_FAILURE = 100;

    private ActivityReceiveBinding binding;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private ProcessCameraProvider cameraProvider;
    private boolean cameraStarted;
    private int decodeFailures;

    private volatile TransferBinder transfer;
    private boolean bound;
    private boolean restoredFromInstanceState;
    private TransferState lastState = TransferState.idle();
    private boolean cameraDenied;

    private final TransferStatusReceiver statusReceiver = new TransferStatusReceiver(null);

    private final ActivityResultLauncher<String> notificationLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                // The transfer works without notifications, nothing else to do.
            });

    private final ActivityResultLauncher<String> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                cameraDenied = !granted;
                onCameraPermissionResult();
                if (PermissionHelper.needsNotificationPrompt()
                        && !PermissionHelper.canPostNotifications(this)) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
            });

    private final TransferService.Listener listener = new TransferService.Listener() {
        @Override
        public void onStateChanged(TransferState state) {
            if (state.mode == TransferState.MODE_RECEIVING) {
                lastState = state;
                updateUi(state);
            }
        }

        @Override
        public void onQrFrame(android.graphics.Bitmap bitmap, int frameNumber, int loopLength,
                              boolean dropped) {
            // Only the sender shows frames.
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            transfer = (TransferBinder) service;
            bound = true;
            transfer.addListener(listener);
            maybeStartSession();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            transfer = null;
            bound = false;
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReceiveBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        restoredFromInstanceState = savedInstanceState != null;
        if (savedInstanceState != null) {
            TransferState finished = savedInstanceState.getParcelable(STATE_FINISHED);
            if (finished != null) {
                lastState = finished;
            }
        }

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build());

        binding.buttonCancel.setOnClickListener(v -> onCancelClicked());
        binding.buttonDetails.setOnClickListener(v -> openRecord());
        binding.buttonGrant.setOnClickListener(v -> cameraLauncher.launch(Manifest.permission.CAMERA));
        binding.buttonSettings.setOnClickListener(
                v -> startActivity(PermissionHelper.appSettingsIntent(this)));

        if (PermissionHelper.hasCameraPermission(this)) {
            onCameraPermissionResult();
        } else {
            showPermissionPanel();
            cameraLauncher.launch(Manifest.permission.CAMERA);
        }
        updateUi(lastState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        statusReceiver.register(this);
        bindService(new Intent(this, TransferService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have granted the permission in the system settings.
        if (PermissionHelper.hasCameraPermission(this) && !cameraStarted) {
            cameraDenied = false;
            onCameraPermissionResult();
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            transfer.removeListener(listener);
            unbindService(connection);
            bound = false;
            transfer = null;
        }
        statusReceiver.unregister(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        analysisExecutor.shutdown();
        scanner.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (lastState.isFinished()) {
            outState.putParcelable(STATE_FINISHED, lastState);
        }
    }

    // ------------------------------------------------------------------ session

    /** Starts a new receiving session unless one is running or a result is being shown. */
    private void maybeStartSession() {
        TransferBinder service = transfer;
        if (service == null || !PermissionHelper.hasCameraPermission(this)) {
            return;
        }
        TransferState current = service.getState();
        if (current.mode == TransferState.MODE_RECEIVING && current.isActive()) {
            return;
        }
        if (restoredFromInstanceState && lastState.isFinished()) {
            return;
        }
        service.clearResult();
        service.startReceiving();
    }

    private void onCancelClicked() {
        TransferBinder service = transfer;
        if (service != null && lastState.isActive()) {
            service.stop();
        }
        finish();
    }

    private void openRecord() {
        if (lastState.recordUri == null) {
            return;
        }
        Intent intent = new Intent(this, DetailsActivity.class);
        intent.setData(Uri.parse(lastState.recordUri));
        startActivity(intent);
        finish();
    }

    // ------------------------------------------------------------------ permission UI

    private void onCameraPermissionResult() {
        if (PermissionHelper.hasCameraPermission(this)) {
            binding.panelPermission.setVisibility(View.GONE);
            startCamera();
            maybeStartSession();
        } else {
            showPermissionPanel();
        }
    }

    private void showPermissionPanel() {
        binding.panelPermission.setVisibility(View.VISIBLE);
        binding.textPermission.setText(cameraDenied
                ? R.string.permission_camera_denied : R.string.permission_camera_rationale);
        // After a permanent denial the system dialog no longer appears, so only the settings
        // page can help.
        boolean canAskAgain = !cameraDenied || PermissionHelper.shouldExplainCamera(this);
        binding.buttonGrant.setVisibility(canAskAgain ? View.VISIBLE : View.GONE);
        binding.buttonSettings.setVisibility(cameraDenied ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ camera

    private void startCamera() {
        if (cameraStarted) {
            return;
        }
        cameraStarted = true;
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                cameraStarted = false;
                CrashReporter.record("Camera provider failed", e);
                Toast.makeText(this, R.string.error_camera, Toast.LENGTH_LONG).show();
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

        ResolutionSelector selector = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        } catch (IllegalArgumentException | IllegalStateException e) {
            cameraStarted = false;
            CrashReporter.record("Cannot bind camera", e);
            Toast.makeText(this, R.string.error_camera, Toast.LENGTH_LONG).show();
        }
    }

    /** Runs on the analyzer thread. Always closes the image proxy. */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(@NonNull ImageProxy proxy) {
        android.media.Image media = proxy.getImage();
        if (media == null) {
            proxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(media, proxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
                .addOnSuccessListener(analysisExecutor, barcodes -> {
                    TransferBinder service = transfer;
                    if (service == null) {
                        return;
                    }
                    for (Barcode barcode : barcodes) {
                        String text = barcode.getRawValue();
                        if (text != null) {
                            service.submitScannedFrame(text);
                        }
                    }
                })
                .addOnFailureListener(analysisExecutor, e -> {
                    decodeFailures++;
                    if (decodeFailures <= LOG_FIRST_FAILURES
                            || decodeFailures % LOG_EVERY_NTH_FAILURE == 0) {
                        CrashReporter.record("Barcode decode failed (" + decodeFailures + ")", e);
                    }
                })
                .addOnCompleteListener(analysisExecutor, task -> proxy.close());
    }

    // ------------------------------------------------------------------ rendering

    private void updateUi(TransferState state) {
        boolean active = state.isActive();
        boolean finished = state.isFinished();
        binding.chunkGrid.setStates(state.chunkStates);
        binding.chunkGrid.setContentDescription(getString(R.string.cd_chunk_grid,
                state.receivedCount + state.recoveredCount, state.totalChunks));

        int progress = (int) Math.round(state.percent);
        binding.progressReceive.setProgress(progress);
        binding.progressReceive.setIndeterminate(active && state.totalChunks == 0);

        if (finished) {
            binding.textStatus.setText(finishedMessage(state));
        } else if (state.totalChunks == 0 && state.fileName == null) {
            binding.textStatus.setText(R.string.receive_waiting);
        } else {
            binding.textStatus.setText(getString(R.string.receive_progress,
                    state.fileName == null ? getString(R.string.unknown_file) : state.fileName,
                    Formatters.formatPercent(state.percent),
                    state.receivedCount + state.recoveredCount, state.totalChunks));
        }

        binding.textLiveStats.setText(getString(R.string.receive_live_stats,
                Math.round(state.decodedFps), state.duplicates, state.recoveredCount,
                state.rejectedFrames));
        binding.textLiveStats.setVisibility(finished ? View.GONE : View.VISIBLE);

        binding.statsPanel.getRoot().setVisibility(finished ? View.VISIBLE : View.GONE);
        if (finished) {
            binding.statsPanel.textStats.setText(StatsPresenter.format(this, state));
        }
        binding.buttonCancel.setText(finished ? R.string.action_done : R.string.action_cancel);
        binding.buttonDetails.setVisibility(
                finished && state.recordUri != null ? View.VISIBLE : View.GONE);
        binding.legendRow.setVisibility(state.totalChunks > 0 ? View.VISIBLE : View.GONE);
    }

    private String finishedMessage(TransferState state) {
        switch (state.status) {
            case TransferState.STATUS_COMPLETED:
                return getString(R.string.receive_done, state.fileName);
            case TransferState.STATUS_INTERRUPTED:
                return getString(R.string.receive_interrupted);
            default:
                return state.message != null ? state.message : getString(R.string.error_unknown);
        }
    }
}
