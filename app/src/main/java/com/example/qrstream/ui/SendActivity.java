package com.example.qrstream.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivitySendBinding;
import com.example.qrstream.protocol.ProtocolConstants;
import com.example.qrstream.protocol.SendPlan;
import com.example.qrstream.receiver.TransferStatusReceiver;
import com.example.qrstream.service.SendRequest;
import com.example.qrstream.service.TransferBinder;
import com.example.qrstream.service.TransferService;
import com.example.qrstream.service.TransferState;
import com.example.qrstream.util.AppPrefs;
import com.example.qrstream.util.CrashReporter;
import com.example.qrstream.util.FilePickerHelper;
import com.example.qrstream.util.Formatters;
import com.example.qrstream.util.PermissionHelper;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Send screen: pick a file, choose parameters, and watch the looping QR code.
 *
 * <p>This class only drives the UI. The sender loop runs in {@link TransferService}; the
 * Activity binds to it, shows what it reports, and survives rotation because no transfer state
 * lives here.</p>
 */
public class SendActivity extends AppCompatActivity {

    private static final String STATE_URI = "picked_uri";
    private static final String STATE_FINISHED = "finished_state";
    private static final int CHUNK_STEP = 50;
    private static final int GROUP_FIRST_ON = 2;
    private static final int LOSS_STEP = 5;
    private static final int MAX_QR_PX = 1200;

    private ActivitySendBinding binding;
    private AppPrefs prefs;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private TransferBinder transfer;
    private boolean bound;
    private boolean restoredFromInstanceState;

    private FilePickerHelper.PickedFile picked;
    private Uri pickedUri;
    private TransferState lastState = TransferState.idle();
    private boolean askedNotificationPermission;

    private float savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private boolean brightnessOverridden;

    private final TransferStatusReceiver statusReceiver = new TransferStatusReceiver(null);

    private final ActivityResultLauncher<String[]> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    if (transfer != null) {
                        transfer.clearResult();
                    }
                    loadFile(uri);
                }
            });

    private final ActivityResultLauncher<String> notificationLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> startSendingNow());

    private final TransferService.Listener listener = new TransferService.Listener() {
        @Override
        public void onStateChanged(TransferState state) {
            lastState = state;
            updateUi(state);
        }

        @Override
        public void onQrFrame(Bitmap bitmap, int frameNumber, int loopLength, boolean dropped) {
            showFrame(bitmap, dropped);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            transfer = (TransferBinder) service;
            bound = true;
            TransferState current = transfer.getState();
            if (!restoredFromInstanceState && !current.isActive()) {
                transfer.clearResult();
            }
            transfer.addListener(listener);
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
        binding = ActivitySendBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new AppPrefs(this);
        restoredFromInstanceState = savedInstanceState != null;

        setUpControls();
        binding.buttonPick.setOnClickListener(v -> pickLauncher.launch(FilePickerHelper.PICKER_MIME_TYPES));
        binding.buttonStart.setOnClickListener(v -> onStartClicked());
        binding.buttonPause.setOnClickListener(v -> onPauseClicked());
        binding.buttonStop.setOnClickListener(v -> {
            if (transfer != null) {
                transfer.stop();
            }
        });

        if (savedInstanceState != null) {
            String uriText = savedInstanceState.getString(STATE_URI);
            if (uriText != null) {
                loadFile(Uri.parse(uriText));
            }
            TransferState finished = savedInstanceState.getParcelable(STATE_FINISHED);
            if (finished != null) {
                lastState = finished;
            }
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
        // Full brightness makes the QR code easier to scan. The previous setting is restored
        // in onPause().
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (!brightnessOverridden) {
            savedBrightness = params.screenBrightness;
            brightnessOverridden = true;
        }
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        getWindow().setAttributes(params);
    }

    @Override
    protected void onPause() {
        if (brightnessOverridden) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = savedBrightness;
            getWindow().setAttributes(params);
            brightnessOverridden = false;
        }
        super.onPause();
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
        ioExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pickedUri != null) {
            outState.putString(STATE_URI, pickedUri.toString());
        }
        if (lastState.isFinished()) {
            outState.putParcelable(STATE_FINISHED, lastState);
        }
    }

    // ------------------------------------------------------------------ controls

    private void setUpControls() {
        binding.seekChunk.setMax((ProtocolConstants.MAX_CHUNK_SIZE - ProtocolConstants.MIN_CHUNK_SIZE) / CHUNK_STEP);
        binding.seekChunk.setProgress((prefs.getChunkSize() - ProtocolConstants.MIN_CHUNK_SIZE) / CHUNK_STEP);
        binding.seekChunk.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            void onChanged(int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.setChunkSize(chunkSizeOf(progress));
                }
                refreshFileInfo();
            }
        });

        binding.seekGroup.setMax(ProtocolConstants.MAX_GROUP_SIZE - GROUP_FIRST_ON + 1);
        binding.seekGroup.setProgress(progressOfGroup(prefs.getGroupSize()));
        binding.seekGroup.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            void onChanged(int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.setGroupSize(groupSizeOf(progress));
                }
                refreshFileInfo();
            }
        });

        binding.seekFps.setMax(ProtocolConstants.MAX_FPS - ProtocolConstants.MIN_FPS);
        binding.seekFps.setProgress(prefs.getFps() - ProtocolConstants.MIN_FPS);
        binding.seekFps.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            void onChanged(int progress, boolean fromUser) {
                int fps = progress + ProtocolConstants.MIN_FPS;
                binding.textFpsValue.setText(getString(R.string.fps_value, fps));
                if (fromUser) {
                    prefs.setFps(fps);
                    if (transfer != null) {
                        transfer.setFps(fps);
                    }
                    refreshFileInfo();
                }
            }
        });

        binding.switchStrongEc.setChecked(prefs.isStrongErrorCorrection());
        binding.switchStrongEc.setOnCheckedChangeListener(
                (button, checked) -> prefs.setStrongErrorCorrection(checked));

        binding.seekLoss.setMax(AppPrefs.MAX_LOSS_PERCENT / LOSS_STEP);
        binding.seekLoss.setProgress(prefs.getLossPercent() / LOSS_STEP);
        binding.seekLoss.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            void onChanged(int progress, boolean fromUser) {
                binding.textLossValue.setText(getString(R.string.percent_value, progress * LOSS_STEP));
                if (fromUser) {
                    prefs.setLossPercent(progress * LOSS_STEP);
                }
            }
        });
        binding.switchSimulate.setChecked(prefs.isSimulateLoss());
        binding.switchSimulate.setOnCheckedChangeListener((button, checked) -> {
            prefs.setSimulateLoss(checked);
            binding.seekLoss.setEnabled(checked);
        });
        binding.seekLoss.setEnabled(prefs.isSimulateLoss());
        // Show the initial labels.
        binding.textChunkValue.setText(getString(R.string.bytes_value, chunkSizeOf(binding.seekChunk.getProgress())));
        binding.textGroupValue.setText(groupLabel(groupSizeOf(binding.seekGroup.getProgress())));
        binding.textFpsValue.setText(getString(R.string.fps_value, binding.seekFps.getProgress() + ProtocolConstants.MIN_FPS));
        binding.textLossValue.setText(getString(R.string.percent_value, binding.seekLoss.getProgress() * LOSS_STEP));
    }

    private static int chunkSizeOf(int progress) {
        return ProtocolConstants.MIN_CHUNK_SIZE + progress * CHUNK_STEP;
    }

    /** Slider position 0 means "no parity", position p >= 1 means K = p + 1. */
    private static int groupSizeOf(int progress) {
        return progress == 0 ? ProtocolConstants.NO_PARITY : progress + GROUP_FIRST_ON - 1;
    }

    private static int progressOfGroup(int groupSize) {
        return groupSize < GROUP_FIRST_ON ? 0 : groupSize - GROUP_FIRST_ON + 1;
    }

    private String groupLabel(int groupSize) {
        return groupSize == ProtocolConstants.NO_PARITY
                ? getString(R.string.group_none) : getString(R.string.group_value, groupSize);
    }

    private int selectedChunkSize() {
        return chunkSizeOf(binding.seekChunk.getProgress());
    }

    private int selectedGroupSize() {
        return groupSizeOf(binding.seekGroup.getProgress());
    }

    private int selectedFps() {
        return binding.seekFps.getProgress() + ProtocolConstants.MIN_FPS;
    }

    // ------------------------------------------------------------------ file loading

    private void loadFile(Uri uri) {
        pickedUri = uri;
        picked = null;
        binding.textFileName.setText(R.string.file_loading);
        binding.textFileError.setVisibility(View.GONE);
        ioExecutor.execute(() -> {
            try {
                FilePickerHelper.PickedFile file = FilePickerHelper.read(getContentResolver(), uri);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    picked = file;
                    refreshFileInfo();
                    updateUi(lastState);
                });
            } catch (FilePickerHelper.FileTooLargeException e) {
                runOnUiThread(() -> showFileError(getString(R.string.error_file_too_large,
                        Formatters.formatSize(ProtocolConstants.MAX_FILE_SIZE_BYTES))));
            } catch (IOException | SecurityException e) {
                CrashReporter.record("Cannot read picked file", e);
                runOnUiThread(() -> showFileError(getString(R.string.error_file_read)));
            }
        });
    }

    private void showFileError(String message) {
        if (isDestroyed()) {
            return;
        }
        pickedUri = null;
        picked = null;
        binding.textFileName.setText(R.string.file_none);
        binding.textFileInfo.setText("");
        binding.textFileError.setText(message);
        binding.textFileError.setVisibility(View.VISIBLE);
        updateUi(lastState);
    }

    private void refreshFileInfo() {
        int chunk = selectedChunkSize();
        int group = selectedGroupSize();
        binding.textChunkValue.setText(getString(R.string.bytes_value, chunk));
        binding.textGroupValue.setText(groupLabel(group));
        if (picked == null) {
            return;
        }
        binding.textFileName.setText(picked.getName());
        int frames = SendPlan.estimateLoopLength(picked.getSize(), chunk, group);
        double seconds = frames / (double) selectedFps();
        binding.textFileInfo.setText(getString(R.string.file_info,
                Formatters.formatSize(picked.getSize()), frames,
                Formatters.formatDuration(Math.round(seconds * 1000))));
    }

    // ------------------------------------------------------------------ actions

    private void onStartClicked() {
        if (picked == null) {
            Toast.makeText(this, R.string.error_pick_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (PermissionHelper.needsNotificationPrompt()
                && !PermissionHelper.canPostNotifications(this) && !askedNotificationPermission) {
            askedNotificationPermission = true;
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        startSendingNow();
    }

    private void startSendingNow() {
        if (picked == null || transfer == null) {
            return;
        }
        int qrPx = Math.min(MAX_QR_PX, Math.max(ProtocolConstants.QR_SIZE_PX, binding.imageQr.getWidth()));
        SendRequest request = new SendRequest(picked.getName(), picked.getMimeType(),
                picked.getBytes(), selectedChunkSize(), selectedGroupSize(), selectedFps(), qrPx,
                binding.switchStrongEc.isChecked(), binding.switchSimulate.isChecked(),
                binding.seekLoss.getProgress() * LOSS_STEP);
        if (!transfer.startSending(request)) {
            Toast.makeText(this, R.string.error_busy, Toast.LENGTH_SHORT).show();
        }
    }

    private void onPauseClicked() {
        if (transfer == null) {
            return;
        }
        if (lastState.status == TransferState.STATUS_PAUSED) {
            transfer.resume();
        } else {
            transfer.pause();
        }
    }

    // ------------------------------------------------------------------ rendering

    private void showFrame(Bitmap bitmap, boolean dropped) {
        if (dropped || bitmap == null) {
            binding.imageQr.setImageResource(R.drawable.ic_frame_lost);
            return;
        }
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setFilterBitmap(false);
        binding.imageQr.setImageDrawable(drawable);
    }

    private void updateUi(TransferState state) {
        boolean mine = state.mode == TransferState.MODE_SENDING;
        boolean running = mine && state.isActive();
        boolean paused = mine && state.status == TransferState.STATUS_PAUSED;
        boolean finished = mine && state.isFinished();

        binding.cardSettings.setVisibility(running ? View.GONE : View.VISIBLE);
        binding.buttonPick.setEnabled(!running);
        binding.buttonStart.setEnabled(!running && picked != null);
        binding.buttonPause.setEnabled(running);
        binding.buttonPause.setText(paused ? R.string.action_resume : R.string.action_pause);
        binding.buttonStop.setEnabled(running);

        if (running) {
            String counter = getString(R.string.frame_counter, state.frameIndex, state.loopLength,
                    state.passes);
            if (state.droppedBySimulation > 0) {
                counter = counter + "\n" + getString(R.string.frame_counter_dropped,
                        state.droppedBySimulation);
            }
            binding.textFrameCounter.setText(counter);
        } else if (finished) {
            binding.textFrameCounter.setText(state.message != null
                    ? state.message : getString(R.string.send_finished));
        } else {
            binding.textFrameCounter.setText(R.string.send_idle_hint);
            binding.imageQr.setImageResource(R.drawable.ic_qr_placeholder);
        }

        binding.statsPanel.getRoot().setVisibility(finished ? View.VISIBLE : View.GONE);
        if (finished) {
            binding.statsPanel.textStats.setText(StatsPresenter.format(this, state));
        }
        binding.imageQr.setContentDescription(running
                ? getString(R.string.cd_qr_running, state.frameIndex, state.loopLength)
                : getString(R.string.cd_qr_idle));
        // Keep the label in sync even when the state came from a rotation restore.
        binding.textFpsValue.setText(String.format(Locale.getDefault(), "%s",
                getString(R.string.fps_value, selectedFps())));
    }
}
