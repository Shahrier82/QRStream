package com.example.qrstream.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.example.qrstream.R;
import com.example.qrstream.protocol.FileMeta;
import com.example.qrstream.protocol.Frame;
import com.example.qrstream.protocol.FrameCodec;
import com.example.qrstream.protocol.LossSimulator;
import com.example.qrstream.protocol.ProtocolConstants;
import com.example.qrstream.protocol.Reassembler;
import com.example.qrstream.protocol.SendPlan;
import com.example.qrstream.provider.TransferContract.Transfers;
import com.example.qrstream.receiver.BatteryLowReceiver;
import com.example.qrstream.sync.SyncWorker;
import com.example.qrstream.ui.HistoryActivity;
import com.example.qrstream.ui.ReceiveActivity;
import com.example.qrstream.ui.SendActivity;
import com.example.qrstream.util.CrashReporter;
import com.example.qrstream.util.FileStore;
import com.example.qrstream.util.Formatters;
import com.example.qrstream.util.PermissionHelper;
import com.example.qrstream.util.QrRenderer;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that owns a transfer.
 *
 * <ul>
 *   <li><b>Sender mode</b>: builds a {@link SendPlan} and shows one QR frame per tick on a
 *       single-thread scheduler, at the configured frames per second.</li>
 *   <li><b>Receiver mode</b>: owns the {@link Reassembler}. The camera stays in
 *       {@code ReceiveActivity}, which forwards decoded text through
 *       {@link TransferBinder#submitScannedFrame(String)}.</li>
 * </ul>
 *
 * <p>While a transfer is active the service shows a persistent progress notification
 * ("Sending: frame 42/75", "Received 42/60 chunks"). A {@link BatteryLowReceiver} pauses the
 * transfer on low battery and posts a notification with a Resume action. Finished transfers are
 * stored through the {@code ContentResolver} and announced with local broadcasts.</p>
 *
 * <p>All protocol logic is delegated to the {@code protocol} package. Slow work (QR
 * rendering, file I/O, database writes) never runs on the main thread.</p>
 */
public class TransferService extends Service {

    // ------------------------------------------------------------------ public contract

    /** Intent action that promotes the service to the foreground. */
    public static final String ACTION_FOREGROUND = "com.example.qrstream.action.FOREGROUND";
    /** Intent action of the notification "Stop" button. */
    public static final String ACTION_STOP = "com.example.qrstream.action.STOP";
    /** Intent action of the notification "Resume" button. */
    public static final String ACTION_RESUME = "com.example.qrstream.action.RESUME";

    /** Broadcast: periodic progress, extra {@link #EXTRA_STATE}. */
    public static final String ACTION_PROGRESS = "com.example.qrstream.action.PROGRESS";
    /** Broadcast: transfer finished successfully, extra {@link #EXTRA_STATE}. */
    public static final String ACTION_COMPLETED = "com.example.qrstream.action.COMPLETED";
    /** Broadcast: transfer failed or was interrupted, extras state and message. */
    public static final String ACTION_FAILED = "com.example.qrstream.action.FAILED";

    /** Broadcast extra: a {@link TransferState}. */
    public static final String EXTRA_STATE = "com.example.qrstream.extra.STATE";
    /** Broadcast extra: failure message, a String. */
    public static final String EXTRA_MESSAGE = "com.example.qrstream.extra.MESSAGE";

    /** Notification channel of the ongoing progress notification. */
    public static final String CHANNEL_PROGRESS = "transfer_progress";
    /** Notification channel of battery warnings and results. */
    public static final String CHANNEL_ALERTS = "transfer_alerts";

    /** Receives updates on the main thread. */
    public interface Listener {
        /**
         * @param state new snapshot; treat as read-only
         */
        void onStateChanged(TransferState state);

        /**
         * A new QR frame is on screen (sender only).
         *
         * @param bitmap      the QR code, or null when the frame was dropped on purpose
         * @param frameNumber 1-based position in the loop
         * @param loopLength  frames in the loop
         * @param dropped     true if the loss simulation swallowed this frame
         */
        void onQrFrame(Bitmap bitmap, int frameNumber, int loopLength, boolean dropped);
    }

    // ------------------------------------------------------------------ constants

    private static final int NOTIF_PROGRESS = 1001;
    private static final int NOTIF_BATTERY = 1002;
    private static final int NOTIF_RESULT = 1003;
    private static final int REQUEST_STOP = 1;
    private static final int REQUEST_RESUME = 2;
    private static final int REQUEST_OPEN = 3;
    private static final long PUBLISH_INTERVAL_MS = 100;
    private static final long NOTIFY_INTERVAL_MS = 500;
    private static final long MS_PER_SECOND = 1000L;
    private static final long IDLE_STOP_DELAY_MS = 30_000L;
    private static final double PERCENT = 100.0;
    /** Fixed seed of the loss simulation, so a demo drops the same frames every time. */
    private static final long DEMO_LOSS_SEED = 2025L;

    // ------------------------------------------------------------------ fields

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final TransferBinder binder = new TransferBinder(this);
    private final Reassembler reassembler = new Reassembler();
    private final Runnable idleStop = () -> {
        if (!getStateSnapshot().isActive()) {
            stopSelf();
        }
    };

    private ExecutorService ioExecutor;
    private ScheduledExecutorService senderExecutor;
    private ScheduledFuture<?> tickFuture;
    private BatteryLowReceiver batteryReceiver;
    private NotificationManager notificationManager;
    private boolean foreground;

    // Session state, guarded by "lock".
    private int mode = TransferState.MODE_IDLE;
    private int status = TransferState.STATUS_IDLE;
    private boolean ending;
    private String endMessage;
    private String recordUriString;
    private boolean verified;

    private long runningSince;
    private long accumulatedMs;
    private long lastPublish;
    private long lastNotify;

    // Sender state.
    private SendPlan plan;
    private List<String> loopTexts;
    private LossSimulator lossSimulator;
    private String sendFileName;
    private long sendFileSize;
    private int fps = ProtocolConstants.DEFAULT_FPS;
    private int qrSizePx = ProtocolConstants.QR_SIZE_PX;
    private boolean strongEc;
    private int loopPos;
    private int frameNumberShown;
    private int framesShown;
    private int passes;
    private int droppedBySimulation;
    private long firstPassMs;
    private Bitmap lastBitmap;
    private boolean lastDropped;

    // Receiver state.
    private String lastText;
    private int rereads;
    private boolean rxTimerStarted;
    private long fpsWindowStart;
    private int fpsWindowCount;
    private float decodedFps;
    private boolean finishingReceive;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ioExecutor = Executors.newSingleThreadExecutor();
        createChannels(this);
        batteryReceiver = new BatteryLowReceiver(this::onBatteryLow);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        mainHandler.removeCallbacks(idleStop);
        return binder;
    }

    /**
     * Stops the service a little after a transfer ended, instead of immediately. A finished
     * result then survives a screen rotation (the Activity unbinds and rebinds within a
     * moment) and stays readable for a while.
     */
    private void scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStop);
        mainHandler.postDelayed(idleStop, IDLE_STOP_DELAY_MS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (!getStateSnapshot().isActive()) {
            scheduleIdleStop();
        }
        // True so that onRebind() is called when the Activity comes back (for example after a
        // rotation), which cancels the pending stop and keeps a finished result visible.
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        mainHandler.removeCallbacks(idleStop);
        super.onRebind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (getStateSnapshot().isActive()) {
                stopTransfer();
            } else {
                stopSelf();
            }
        } else if (ACTION_RESUME.equals(action)) {
            resume();
        } else if (ACTION_FOREGROUND.equals(action)) {
            promoteToForeground();
        } else if (!getStateSnapshot().isActive()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user swiped the app away: do not keep showing QR codes or a notification.
        if (getStateSnapshot().isActive()) {
            stopTransfer();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        cancelTick();
        if (senderExecutor != null) {
            senderExecutor.shutdownNow();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        batteryReceiver.unregister(this);
        notificationManager.cancel(NOTIF_PROGRESS);
        notificationManager.cancel(NOTIF_BATTERY);
        listeners.clear();
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (lock) {
            lastBitmap = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ binder operations

    void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.onStateChanged(getStateSnapshot());
        Bitmap bitmap;
        boolean dropped;
        int frame;
        int loop;
        synchronized (lock) {
            bitmap = lastBitmap;
            dropped = lastDropped;
            frame = frameNumberShown;
            loop = loopTexts == null ? 0 : loopTexts.size();
        }
        if (frame > 0) {
            listener.onQrFrame(bitmap, frame, loop, dropped);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    TransferState getStateSnapshot() {
        synchronized (lock) {
            return buildStateLocked();
        }
    }

    boolean startSending(SendRequest request) {
        synchronized (lock) {
            if (isActiveLocked()) {
                return false;
            }
            resetSessionLocked();
            mode = TransferState.MODE_SENDING;
            status = TransferState.STATUS_RUNNING;
            sendFileName = request.getFileName();
            sendFileSize = request.getData().length;
            fps = clamp(request.getFps(), ProtocolConstants.MIN_FPS, ProtocolConstants.MAX_FPS);
            qrSizePx = Math.max(request.getQrSizePx(), ProtocolConstants.QR_SIZE_PX);
            strongEc = request.isStrongErrorCorrection();
        }
        beginForegroundSession();
        senderExecutor = Executors.newSingleThreadScheduledExecutor();
        senderExecutor.execute(() -> prepareSender(request));
        publishState(true);
        return true;
    }

    boolean startReceiving() {
        synchronized (lock) {
            if (isActiveLocked()) {
                return false;
            }
            resetSessionLocked();
            mode = TransferState.MODE_RECEIVING;
            status = TransferState.STATUS_RUNNING;
        }
        beginForegroundSession();
        publishState(true);
        return true;
    }

    void pause(boolean byBattery) {
        synchronized (lock) {
            if (status != TransferState.STATUS_RUNNING) {
                return;
            }
            status = TransferState.STATUS_PAUSED;
            stopClockLocked();
        }
        cancelTick();
        if (byBattery) {
            postBatteryNotification();
        }
        publishState(true);
    }

    void resume() {
        boolean sender;
        synchronized (lock) {
            if (status != TransferState.STATUS_PAUSED) {
                return;
            }
            status = TransferState.STATUS_RUNNING;
            sender = mode == TransferState.MODE_SENDING;
            if (sender || rxTimerStarted) {
                startClockLocked();
            }
        }
        notificationManager.cancel(NOTIF_BATTERY);
        if (sender) {
            scheduleTick(0);
        }
        publishState(true);
    }

    void setFps(int newFps) {
        synchronized (lock) {
            fps = clamp(newFps, ProtocolConstants.MIN_FPS, ProtocolConstants.MAX_FPS);
        }
        publishState(true);
    }

    void clearResult() {
        synchronized (lock) {
            if (isActiveLocked()) {
                return;
            }
            resetSessionLocked();
        }
        publishState(true);
    }

    /** Stops the active transfer (sender or receiver) and records it in the history. */
    void stopTransfer() {
        int currentMode;
        synchronized (lock) {
            if (!isActiveLocked() || ending) {
                return;
            }
            ending = true;
            currentMode = mode;
            stopClockLocked();
        }
        cancelTick();
        if (senderExecutor != null) {
            senderExecutor.shutdownNow();
        }
        if (currentMode == TransferState.MODE_SENDING) {
            stopSender();
        } else {
            stopReceiver();
        }
    }

    // ------------------------------------------------------------------ sender

    private void prepareSender(SendRequest request) {
        try {
            int sessionId = SendPlan.newSessionId(new SecureRandom());
            SendPlan built = SendPlan.build(request.getFileName(), request.getMimeType(),
                    request.getData(), request.getChunkSize(), request.getGroupSize(), sessionId);
            List<String> texts = new ArrayList<>(built.getLoopLength());
            for (Frame frame : built.getLoopFrames()) {
                texts.add(FrameCodec.encodeToString(frame));
            }
            LossSimulator simulator = request.isSimulateLoss()
                    ? new LossSimulator(request.getLossPercent() / PERCENT, DEMO_LOSS_SEED) : null;
            synchronized (lock) {
                if (status != TransferState.STATUS_RUNNING) {
                    return;
                }
                plan = built;
                loopTexts = texts;
                lossSimulator = simulator;
                startClockLocked();
            }
            publishState(true);
            scheduleTick(0);
        } catch (IllegalArgumentException e) {
            CrashReporter.record("Cannot build send plan", e);
            failWithoutRecord(getString(R.string.error_send_failed, e.getMessage()));
        }
    }

    private void sendTick() {
        long startedAt = SystemClock.elapsedRealtime();
        try {
            int pos;
            String text;
            boolean strong;
            int size;
            synchronized (lock) {
                if (mode != TransferState.MODE_SENDING || status != TransferState.STATUS_RUNNING
                        || loopTexts == null) {
                    return;
                }
                pos = loopPos;
                loopPos = (loopPos + 1) % loopTexts.size();
                text = loopTexts.get(pos);
                strong = strongEc;
                size = qrSizePx;
            }
            boolean dropped = lossSimulator != null && lossSimulator.shouldDrop();
            Bitmap bitmap = dropped ? null : QrRenderer.render(text, size, strong);
            int loopLength;
            synchronized (lock) {
                if (status != TransferState.STATUS_RUNNING) {
                    return;
                }
                loopLength = loopTexts.size();
                framesShown++;
                frameNumberShown = pos + 1;
                if (dropped) {
                    droppedBySimulation++;
                }
                if (pos == loopLength - 1) {
                    passes++;
                    if (passes == 1) {
                        firstPassMs = elapsedLocked();
                    }
                }
                lastBitmap = bitmap;
                lastDropped = dropped;
            }
            dispatchFrame(bitmap, pos + 1, loopLength, dropped);
            publishState(false);
        } catch (Throwable t) {
            CrashReporter.record("Sender tick failed", t);
            failWithoutRecord(getString(R.string.error_send_failed, String.valueOf(t.getMessage())));
            return;
        }
        long spent = SystemClock.elapsedRealtime() - startedAt;
        long period;
        synchronized (lock) {
            period = MS_PER_SECOND / fps;
        }
        scheduleTick(Math.max(0, period - spent));
    }

    private void scheduleTick(long delayMs) {
        synchronized (lock) {
            if (senderExecutor == null || senderExecutor.isShutdown()) {
                return;
            }
            try {
                // Keep exactly one pending tick. Without this, pause followed quickly by
                // resume could leave two chains of ticks running and double the frame rate.
                if (tickFuture != null) {
                    tickFuture.cancel(false);
                }
                tickFuture = senderExecutor.schedule(this::sendTick, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // The service is shutting down.
            }
        }
    }

    private void cancelTick() {
        synchronized (lock) {
            if (tickFuture != null) {
                tickFuture.cancel(false);
                tickFuture = null;
            }
        }
    }

    private void stopSender() {
        final boolean fullPass;
        final FileMeta meta;
        synchronized (lock) {
            fullPass = passes >= 1;
            meta = plan == null ? null : plan.getMeta();
        }
        ioExecutor.execute(() -> {
            Uri record = null;
            if (meta != null) {
                record = insertRecord(meta.getFileName(), meta.getFileSize(),
                        Transfers.DIRECTION_SENT, meta.getTotalChunks(), 0,
                        fullPass ? Transfers.STATUS_COMPLETED : Transfers.STATUS_INTERRUPTED,
                        meta.getSha256(), null);
            }
            if (fullPass) {
                endSession(TransferState.STATUS_COMPLETED, null, record, false);
            } else {
                endSession(TransferState.STATUS_INTERRUPTED,
                        getString(R.string.msg_stopped_early), record, false);
            }
        });
    }

    // ------------------------------------------------------------------ receiver

    /**
     * Pushes the text of one decoded QR code into the reassembler. Safe to call from any
     * thread, in particular the camera analyzer thread.
     *
     * <p>The camera reads the same QR code many times while it stays on screen. Consecutive
     * identical texts are skipped (counted as "re-reads"), so the duplicate counter of the
     * reassembler only counts frames that were genuinely broadcast again by a later loop.</p>
     */
    void submitScannedFrame(String text) {
        if (text == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean startFinish = false;
        synchronized (lock) {
            if (mode != TransferState.MODE_RECEIVING || status != TransferState.STATUS_RUNNING
                    || ending) {
                return;
            }
            if (text.equals(lastText)) {
                rereads++;
                return;
            }
            lastText = text;
            if (!rxTimerStarted) {
                rxTimerStarted = true;
                startClockLocked();
                fpsWindowStart = now;
            }
            fpsWindowCount++;
            if (now - fpsWindowStart >= MS_PER_SECOND) {
                decodedFps = fpsWindowCount * (float) MS_PER_SECOND / (now - fpsWindowStart);
                fpsWindowStart = now;
                fpsWindowCount = 0;
            }
        }
        // The reassembler is synchronized itself; do not hold the service lock while using it.
        reassembler.accept(text);
        synchronized (lock) {
            if (reassembler.isComplete() && !finishingReceive) {
                finishingReceive = true;
                ending = true;
                stopClockLocked();
                startFinish = true;
            }
        }
        if (startFinish) {
            ioExecutor.execute(this::finishReceive);
        }
        publishState(startFinish);
    }

    private void finishReceive() {
        FileMeta meta = reassembler.getMeta();
        byte[] bytes = reassembler.getAssembledBytes();
        Reassembler.Verification verification = reassembler.getVerification();
        int recovered = reassembler.getRecoveredCount();
        if (meta == null || bytes == null) {
            endSession(TransferState.STATUS_FAILED, getString(R.string.error_unknown), null, false);
            return;
        }
        String safeName = Formatters.sanitizeFileName(meta.getFileName());
        if (verification != Reassembler.Verification.OK) {
            Uri record = insertRecord(safeName, meta.getFileSize(), Transfers.DIRECTION_RECEIVED,
                    meta.getTotalChunks(), recovered, Transfers.STATUS_FAILED, meta.getSha256(), null);
            endSession(TransferState.STATUS_FAILED, getString(R.string.error_hash_mismatch),
                    record, false);
            return;
        }
        try {
            File file = FileStore.saveReceived(this, safeName, bytes);
            Uri record = insertRecord(file.getName(), meta.getFileSize(),
                    Transfers.DIRECTION_RECEIVED, meta.getTotalChunks(), recovered,
                    Transfers.STATUS_COMPLETED, meta.getSha256(), file.getAbsolutePath());
            endSession(TransferState.STATUS_COMPLETED, null, record, true);
        } catch (IOException e) {
            CrashReporter.record("Cannot store received file", e);
            Uri record = insertRecord(safeName, meta.getFileSize(), Transfers.DIRECTION_RECEIVED,
                    meta.getTotalChunks(), recovered, Transfers.STATUS_FAILED, meta.getSha256(), null);
            endSession(TransferState.STATUS_FAILED, getString(R.string.error_storage), record, false);
        }
    }

    private void stopReceiver() {
        final FileMeta meta = reassembler.getMeta();
        final int recovered = reassembler.getRecoveredCount();
        ioExecutor.execute(() -> {
            if (meta == null) {
                // Nothing was identified yet, so there is nothing worth keeping in the history.
                abortToIdle();
                return;
            }
            Uri record = insertRecord(Formatters.sanitizeFileName(meta.getFileName()),
                    meta.getFileSize(), Transfers.DIRECTION_RECEIVED, meta.getTotalChunks(),
                    recovered, Transfers.STATUS_INTERRUPTED, meta.getSha256(), null);
            endSession(TransferState.STATUS_INTERRUPTED,
                    getString(R.string.msg_stopped_early), record, false);
        });
    }

    // ------------------------------------------------------------------ finishing

    private void endSession(int finalStatus, String message, Uri record, boolean isVerified) {
        TransferState snapshot;
        synchronized (lock) {
            stopClockLocked();
            status = finalStatus;
            ending = true;
            endMessage = message;
            recordUriString = record == null ? null : record.toString();
            verified = isVerified;
            snapshot = buildStateLocked();
            dispatchStateLocked(snapshot);
        }
        cancelTick();
        sendTerminalBroadcast(snapshot);
        leaveForeground(snapshot);
        batteryReceiver.unregister(this);
        notificationManager.cancel(NOTIF_BATTERY);
        SyncWorker.enqueue(getApplicationContext());
        scheduleIdleStop();
    }

    private void failWithoutRecord(String message) {
        synchronized (lock) {
            if (ending) {
                return;
            }
            ending = true;
        }
        if (senderExecutor != null) {
            // shutdown(), not shutdownNow(): this may run on the sender thread itself.
            senderExecutor.shutdown();
        }
        endSession(TransferState.STATUS_FAILED, message, null, false);
    }

    private void abortToIdle() {
        synchronized (lock) {
            resetSessionLocked();
            dispatchStateLocked(buildStateLocked());
        }
        cancelTick();
        leaveForeground(null);
        batteryReceiver.unregister(this);
        notificationManager.cancel(NOTIF_BATTERY);
        scheduleIdleStop();
    }

    private Uri insertRecord(String name, long size, String direction, int chunks, int recovered,
                             String recordStatus, String sha256, String path) {
        try {
            return getContentResolver().insert(Transfers.CONTENT_URI,
                    Transfers.buildValues(name, size, direction, chunks, recovered, recordStatus,
                            sha256, path));
        } catch (RuntimeException e) {
            CrashReporter.record("Cannot write history row", e);
            return null;
        }
    }

    private void sendTerminalBroadcast(TransferState snapshot) {
        String action = snapshot.status == TransferState.STATUS_COMPLETED
                ? ACTION_COMPLETED : ACTION_FAILED;
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, snapshot.copyWithoutGrid());
        if (snapshot.message != null) {
            intent.putExtra(EXTRA_MESSAGE, snapshot.message);
        }
        sendBroadcast(intent);
    }

    // ------------------------------------------------------------------ battery

    private void onBatteryLow() {
        if (getStateSnapshot().status == TransferState.STATUS_RUNNING) {
            pause(true);
        }
    }

    private void postBatteryNotification() {
        if (!PermissionHelper.canPostNotifications(this)) {
            return;
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_battery_title))
                .setContentText(getString(R.string.notif_battery_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.notif_battery_text)))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openUiIntent())
                .addAction(R.drawable.ic_play, getString(R.string.notif_action_resume),
                        servicePendingIntent(ACTION_RESUME, REQUEST_RESUME))
                .build();
        notificationManager.notify(NOTIF_BATTERY, notification);
    }

    // ------------------------------------------------------------------ state building

    private boolean isActiveLocked() {
        return status == TransferState.STATUS_RUNNING || status == TransferState.STATUS_PAUSED;
    }

    private long elapsedLocked() {
        return accumulatedMs
                + (runningSince != 0 ? SystemClock.elapsedRealtime() - runningSince : 0);
    }

    private void startClockLocked() {
        if (runningSince == 0) {
            runningSince = SystemClock.elapsedRealtime();
        }
    }

    private void stopClockLocked() {
        if (runningSince != 0) {
            accumulatedMs += SystemClock.elapsedRealtime() - runningSince;
            runningSince = 0;
        }
    }

    private void resetSessionLocked() {
        mode = TransferState.MODE_IDLE;
        status = TransferState.STATUS_IDLE;
        ending = false;
        endMessage = null;
        recordUriString = null;
        verified = false;
        runningSince = 0;
        accumulatedMs = 0;
        lastPublish = 0;
        lastNotify = 0;
        plan = null;
        loopTexts = null;
        lossSimulator = null;
        sendFileName = null;
        sendFileSize = 0;
        loopPos = 0;
        frameNumberShown = 0;
        framesShown = 0;
        passes = 0;
        droppedBySimulation = 0;
        firstPassMs = 0;
        lastBitmap = null;
        lastDropped = false;
        lastText = null;
        rereads = 0;
        rxTimerStarted = false;
        fpsWindowStart = 0;
        fpsWindowCount = 0;
        decodedFps = 0f;
        finishingReceive = false;
        reassembler.reset();
    }

    private TransferState buildStateLocked() {
        TransferState s = new TransferState();
        s.mode = mode;
        s.status = status;
        s.message = endMessage;
        s.recordUri = recordUriString;
        s.verified = verified;
        s.elapsedMs = elapsedLocked();
        if (mode == TransferState.MODE_SENDING) {
            fillSenderStateLocked(s);
        } else if (mode == TransferState.MODE_RECEIVING) {
            fillReceiverStateLocked(s);
        }
        return s;
    }

    private void fillSenderStateLocked(TransferState s) {
        s.fps = fps;
        s.fileName = sendFileName;
        s.fileSize = sendFileSize;
        if (plan != null) {
            FileMeta meta = plan.getMeta();
            s.fileName = meta.getFileName();
            s.totalChunks = meta.getTotalChunks();
            s.chunkSize = meta.getChunkSize();
            s.groupSize = meta.getGroupSize();
            s.overheadPercent = plan.getOverheadPercent();
        }
        s.loopLength = loopTexts == null ? 0 : loopTexts.size();
        s.frameIndex = frameNumberShown;
        s.passes = passes;
        s.droppedBySimulation = droppedBySimulation;
        s.framesTotal = framesShown;
        s.percent = s.loopLength == 0 ? 0 : PERCENT * frameNumberShown / s.loopLength;
        s.throughputBytesPerSecond = firstPassMs > 0
                ? sendFileSize / (firstPassMs / (double) MS_PER_SECOND) : 0;
    }

    private void fillReceiverStateLocked(TransferState s) {
        FileMeta meta = reassembler.getMeta();
        if (meta != null) {
            s.fileName = meta.getFileName();
            s.fileSize = meta.getFileSize();
            s.totalChunks = meta.getTotalChunks();
            s.chunkSize = meta.getChunkSize();
            s.groupSize = meta.getGroupSize();
        }
        s.receivedCount = reassembler.getReceivedCount();
        s.recoveredCount = reassembler.getRecoveredCount();
        s.parityReceived = reassembler.getParityReceivedCount();
        s.rejectedFrames = reassembler.getInvalidFrameCount() + reassembler.getIgnoredFrameCount();
        s.rereads = rereads;
        s.decodedFps = decodedFps;
        s.chunkStates = reassembler.getChunkStates();
        s.framesUseful = reassembler.getFramesAccepted();
        s.duplicates = reassembler.getDuplicateCount();
        s.framesTotal = s.framesUseful + s.duplicates;
        s.percent = reassembler.getPercentComplete();
        if (s.elapsedMs > 0 && meta != null) {
            double bytesSoFar = s.fileSize * s.percent / PERCENT;
            s.throughputBytesPerSecond = bytesSoFar / (s.elapsedMs / (double) MS_PER_SECOND);
        }
        if (s.totalChunks > 0 && s.framesTotal > s.totalChunks) {
            s.overheadPercent = PERCENT * (s.framesTotal - s.totalChunks) / s.totalChunks;
        }
    }

    // ------------------------------------------------------------------ publishing

    private void publishState(boolean force) {
        long now = SystemClock.elapsedRealtime();
        TransferState snapshot;
        boolean notify;
        synchronized (lock) {
            if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) {
                return;
            }
            lastPublish = now;
            snapshot = buildStateLocked();
            notify = force || now - lastNotify >= NOTIFY_INTERVAL_MS;
            if (notify) {
                lastNotify = now;
            }
            // Posting while holding the lock keeps the order of snapshots on the main thread
            // identical to the order in which they were built.
            dispatchStateLocked(snapshot);
        }
        if (notify && snapshot.isActive()) {
            updateProgressNotification(snapshot);
            sendProgressBroadcast(snapshot);
        }
    }

    /** Must be called with {@code lock} held. */
    private void dispatchStateLocked(TransferState snapshot) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onStateChanged(snapshot);
            }
        });
    }

    private void dispatchFrame(Bitmap bitmap, int frameNumber, int loopLength, boolean dropped) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onQrFrame(bitmap, frameNumber, loopLength, dropped);
            }
        });
    }

    private void sendProgressBroadcast(TransferState snapshot) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, snapshot.copyWithoutGrid());
        sendBroadcast(intent);
    }

    // ------------------------------------------------------------------ foreground / notifications

    /** Starts this service as a started service so it may call startForeground(). */
    private void beginForegroundSession() {
        mainHandler.removeCallbacks(idleStop);
        batteryReceiver.register(this);
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), TransferService.class)
                            .setAction(ACTION_FOREGROUND));
        } catch (RuntimeException e) {
            // For example the app was moved to the background at this very moment. The bound
            // service keeps working while the Activity is visible.
            CrashReporter.record("Cannot start foreground service", e);
        }
    }

    private void promoteToForeground() {
        try {
            ServiceCompat.startForeground(this, NOTIF_PROGRESS,
                    buildProgressNotification(getStateSnapshot()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            foreground = true;
        } catch (RuntimeException e) {
            CrashReporter.record("startForeground failed", e);
        }
    }

    private void leaveForeground(TransferState result) {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        notificationManager.cancel(NOTIF_PROGRESS);
        if (result != null && PermissionHelper.canPostNotifications(this)) {
            notificationManager.notify(NOTIF_RESULT, buildResultNotification(result));
        }
    }

    private void updateProgressNotification(TransferState snapshot) {
        if (foreground) {
            notificationManager.notify(NOTIF_PROGRESS, buildProgressNotification(snapshot));
        }
    }

    private Notification buildProgressNotification(TransferState s) {
        boolean sending = s.mode == TransferState.MODE_SENDING;
        String title = getString(sending ? R.string.notif_sending_title
                : R.string.notif_receiving_title);
        String text;
        int max;
        int current;
        if (sending) {
            max = s.loopLength;
            current = s.frameIndex;
            text = s.loopLength == 0 ? getString(R.string.notif_preparing)
                    : getString(R.string.notif_sending_text, s.frameIndex, s.loopLength);
        } else {
            max = s.totalChunks;
            current = s.receivedCount + s.recoveredCount;
            text = s.totalChunks == 0 ? getString(R.string.notif_waiting)
                    : getString(R.string.notif_receiving_text, current, s.totalChunks);
        }
        if (s.status == TransferState.STATUS_PAUSED) {
            text = text + " " + getString(R.string.notif_paused_suffix);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setProgress(max, Math.min(current, max), max == 0)
                .setContentIntent(openUiIntent())
                .addAction(R.drawable.ic_stop, getString(R.string.notif_action_stop),
                        servicePendingIntent(ACTION_STOP, REQUEST_STOP));
        if (s.status == TransferState.STATUS_PAUSED) {
            builder.addAction(R.drawable.ic_play, getString(R.string.notif_action_resume),
                    servicePendingIntent(ACTION_RESUME, REQUEST_RESUME));
        }
        return builder.build();
    }

    private Notification buildResultNotification(TransferState s) {
        String name = s.fileName != null ? s.fileName : getString(R.string.unknown_file);
        String title;
        String text;
        if (s.status == TransferState.STATUS_COMPLETED) {
            title = getString(s.mode == TransferState.MODE_RECEIVING
                    ? R.string.notif_done_received : R.string.notif_done_sent);
            text = name;
        } else {
            title = getString(R.string.notif_failed_title);
            text = s.message != null ? name + ": " + s.message : name;
        }
        Intent open = new Intent(this, HistoryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, REQUEST_OPEN, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
    }

    private PendingIntent openUiIntent() {
        Class<?> target = getStateSnapshot().mode == TransferState.MODE_RECEIVING
                ? ReceiveActivity.class : SendActivity.class;
        Intent open = new Intent(this, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, REQUEST_OPEN, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, TransferService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Creates the two notification channels. Safe to call repeatedly.
     *
     * @param context any context
     */
    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress_name),
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription(context.getString(R.string.channel_progress_desc));
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription(context.getString(R.string.channel_alerts_desc));
        manager.createNotificationChannel(progress);
        manager.createNotificationChannel(alerts);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
