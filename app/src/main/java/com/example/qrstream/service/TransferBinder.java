package com.example.qrstream.service;

import android.os.Binder;

/**
 * Interface that Activities use to drive {@link TransferService}.
 *
 * <p>The service runs in the same process, so this is a plain local binder: every method
 * simply forwards to the service. Methods may be called from any thread unless noted.
 * Listener callbacks always arrive on the main thread.</p>
 */
public final class TransferBinder extends Binder {

    private final TransferService service;

    TransferBinder(TransferService service) {
        this.service = service;
    }

    /**
     * Registers a listener and immediately delivers the current state to it.
     *
     * @param listener receives state changes and QR frames on the main thread
     */
    public void addListener(TransferService.Listener listener) {
        service.addListener(listener);
    }

    /**
     * @param listener a listener added earlier
     */
    public void removeListener(TransferService.Listener listener) {
        service.removeListener(listener);
    }

    /**
     * Starts the sender loop. Does nothing and returns false if a transfer is already active.
     *
     * @param request file and settings
     * @return true if sending started
     */
    public boolean startSending(SendRequest request) {
        return service.startSending(request);
    }

    /** Pauses the sender loop (the frame on screen stays). */
    public void pause() {
        service.pause(false);
    }

    /** Resumes a paused transfer. */
    public void resume() {
        service.resume();
    }

    /**
     * Changes the frame rate of a running sender.
     *
     * @param fps frames per second, 1 to 10
     */
    public void setFps(int fps) {
        service.setFps(fps);
    }

    /** Stops the active transfer, sender or receiver, and records it in the history. */
    public void stop() {
        service.stopTransfer();
    }

    /**
     * Starts a receiving session with an empty reassembler. Does nothing and returns false if
     * a transfer is already active.
     *
     * @return true if receiving started
     */
    public boolean startReceiving() {
        return service.startReceiving();
    }

    /**
     * Pushes the text of one decoded QR code into the reassembler. Safe to call from the
     * camera analyzer thread.
     *
     * @param text raw QR text
     */
    public void submitScannedFrame(String text) {
        service.submitScannedFrame(text);
    }

    /** @return a copy of the current state */
    public TransferState getState() {
        return service.getStateSnapshot();
    }

    /** Forgets a finished result so the screen starts clean. Ignored while a transfer runs. */
    public void clearResult() {
        service.clearResult();
    }
}
