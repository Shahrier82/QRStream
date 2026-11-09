package com.example.qrstream.service;

/** Everything {@link TransferService} needs to start sending one file. Immutable. */
public final class SendRequest {

    private final String fileName;
    private final String mimeType;
    private final byte[] data;
    private final int chunkSize;
    private final int groupSize;
    private final int fps;
    private final int qrSizePx;
    private final boolean strongErrorCorrection;
    private final boolean simulateLoss;
    private final int lossPercent;

    /**
     * @param fileName              display name
     * @param mimeType              MIME type, may be null
     * @param data                  file bytes (the caller must not change them afterwards)
     * @param chunkSize             bytes per DATA chunk
     * @param groupSize             parity group size K, 0 for no parity
     * @param fps                   frames per second
     * @param qrSizePx              QR bitmap edge in pixels (at least 600 is used)
     * @param strongErrorCorrection true for QR level M, false for L
     * @param simulateLoss          true to drop frames on purpose (demo mode)
     * @param lossPercent           percentage of frames to drop when simulating
     */
    public SendRequest(String fileName, String mimeType, byte[] data, int chunkSize,
                       int groupSize, int fps, int qrSizePx, boolean strongErrorCorrection,
                       boolean simulateLoss, int lossPercent) {
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.data = data;
        this.chunkSize = chunkSize;
        this.groupSize = groupSize;
        this.fps = fps;
        this.qrSizePx = qrSizePx;
        this.strongErrorCorrection = strongErrorCorrection;
        this.simulateLoss = simulateLoss;
        this.lossPercent = lossPercent;
    }

    /** @return display name */
    public String getFileName() {
        return fileName;
    }

    /** @return MIME type, may be null */
    public String getMimeType() {
        return mimeType;
    }

    /** @return file bytes */
    public byte[] getData() {
        return data;
    }

    /** @return bytes per chunk */
    public int getChunkSize() {
        return chunkSize;
    }

    /** @return parity group size, 0 for none */
    public int getGroupSize() {
        return groupSize;
    }

    /** @return frames per second */
    public int getFps() {
        return fps;
    }

    /** @return QR bitmap edge in pixels */
    public int getQrSizePx() {
        return qrSizePx;
    }

    /** @return true for QR error correction level M */
    public boolean isStrongErrorCorrection() {
        return strongErrorCorrection;
    }

    /** @return true if frames are dropped on purpose */
    public boolean isSimulateLoss() {
        return simulateLoss;
    }

    /** @return percentage of frames dropped when simulating */
    public int getLossPercent() {
        return lossPercent;
    }
}
