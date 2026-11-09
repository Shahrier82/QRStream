package com.example.qrstream.service;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Snapshot of a transfer, handed from {@link TransferService} to the UI (through the binder
 * listener and through broadcasts).
 *
 * <p>A plain data holder with public fields. Instances given to listeners are private copies,
 * so the UI may keep them.</p>
 */
public final class TransferState implements Parcelable {

    /** Nothing is happening. */
    public static final int MODE_IDLE = 0;
    /** This device is showing QR codes. */
    public static final int MODE_SENDING = 1;
    /** This device is scanning QR codes. */
    public static final int MODE_RECEIVING = 2;

    /** No transfer is active and no result is waiting. */
    public static final int STATUS_IDLE = 0;
    /** A transfer is running. */
    public static final int STATUS_RUNNING = 1;
    /** A transfer is paused (by the user or by the low-battery receiver). */
    public static final int STATUS_PAUSED = 2;
    /** The transfer finished successfully. */
    public static final int STATUS_COMPLETED = 3;
    /** The transfer failed (for example hash mismatch or storage error). */
    public static final int STATUS_FAILED = 4;
    /** The transfer was stopped before it finished. */
    public static final int STATUS_INTERRUPTED = 5;

    /** One of the MODE_ constants. */
    public int mode = MODE_IDLE;
    /** One of the STATUS_ constants. */
    public int status = STATUS_IDLE;

    /** File name, or null while unknown. */
    public String fileName;
    /** File size in bytes. */
    public long fileSize;
    /** Number of DATA chunks. */
    public int totalChunks;
    /** Bytes per chunk. */
    public int chunkSize;
    /** Parity group size K, 0 when parity is off. */
    public int groupSize;
    /** Frames per second (sender). */
    public int fps;

    /** Sender: 1-based position in the current loop of the frame on screen. */
    public int frameIndex;
    /** Sender: frames in one loop pass. */
    public int loopLength;
    /** Sender: completed passes through the loop. */
    public int passes;
    /** Sender: frames deliberately dropped by the loss simulation. */
    public int droppedBySimulation;

    /** Receiver: chunks that arrived as DATA frames. */
    public int receivedCount;
    /** Receiver: chunks rebuilt from parity. */
    public int recoveredCount;
    /** Receiver: PARITY frames that arrived. */
    public int parityReceived;
    /** Receiver: frames rejected (bad CRC, wrong session, out of range). */
    public int rejectedFrames;
    /** Receiver: consecutive camera reads of the same QR code that were skipped. */
    public int rereads;
    /** Receiver: distinct frames decoded per second. */
    public float decodedFps;
    /** Receiver: state of every chunk, 0 missing, 1 received, 2 recovered; null for the sender. */
    public byte[] chunkStates;

    /** Frames shown (sender) or valid frames processed (receiver). */
    public int framesTotal;
    /** Receiver: new frames that were stored. */
    public int framesUseful;
    /** Receiver: valid frames that had been received before. */
    public int duplicates;

    /** Progress from 0 to 100. */
    public double percent;
    /** Active transfer time in milliseconds (paused time excluded). */
    public long elapsedMs;
    /** Effective throughput in bytes per second. */
    public double throughputBytesPerSecond;
    /** Extra frames beyond the data chunks, in percent of the data chunks. */
    public double overheadPercent;
    /** Receiver: true if the SHA-256 matched. */
    public boolean verified;
    /** Human-readable message for failures, or null. */
    public String message;
    /** Receiver: content URI of the history row once saved, or null. */
    public String recordUri;

    /** Creates an idle state. */
    public TransferState() {
    }

    /** @return a new idle state */
    public static TransferState idle() {
        return new TransferState();
    }

    /** @return true while a transfer is running or paused */
    public boolean isActive() {
        return status == STATUS_RUNNING || status == STATUS_PAUSED;
    }

    /** @return true when the transfer has ended in any way */
    public boolean isFinished() {
        return status == STATUS_COMPLETED || status == STATUS_FAILED
                || status == STATUS_INTERRUPTED;
    }

    /** @return a copy without the chunk grid, small enough for frequent broadcasts */
    public TransferState copyWithoutGrid() {
        Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            TransferState copy = CREATOR.createFromParcel(parcel);
            copy.chunkStates = null;
            return copy;
        } finally {
            parcel.recycle();
        }
    }

    // ------------------------------------------------------------------ Parcelable

    private TransferState(Parcel in) {
        mode = in.readInt();
        status = in.readInt();
        fileName = in.readString();
        fileSize = in.readLong();
        totalChunks = in.readInt();
        chunkSize = in.readInt();
        groupSize = in.readInt();
        fps = in.readInt();
        frameIndex = in.readInt();
        loopLength = in.readInt();
        passes = in.readInt();
        droppedBySimulation = in.readInt();
        receivedCount = in.readInt();
        recoveredCount = in.readInt();
        parityReceived = in.readInt();
        rejectedFrames = in.readInt();
        rereads = in.readInt();
        decodedFps = in.readFloat();
        chunkStates = in.createByteArray();
        framesTotal = in.readInt();
        framesUseful = in.readInt();
        duplicates = in.readInt();
        percent = in.readDouble();
        elapsedMs = in.readLong();
        throughputBytesPerSecond = in.readDouble();
        overheadPercent = in.readDouble();
        verified = in.readInt() != 0;
        message = in.readString();
        recordUri = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mode);
        dest.writeInt(status);
        dest.writeString(fileName);
        dest.writeLong(fileSize);
        dest.writeInt(totalChunks);
        dest.writeInt(chunkSize);
        dest.writeInt(groupSize);
        dest.writeInt(fps);
        dest.writeInt(frameIndex);
        dest.writeInt(loopLength);
        dest.writeInt(passes);
        dest.writeInt(droppedBySimulation);
        dest.writeInt(receivedCount);
        dest.writeInt(recoveredCount);
        dest.writeInt(parityReceived);
        dest.writeInt(rejectedFrames);
        dest.writeInt(rereads);
        dest.writeFloat(decodedFps);
        dest.writeByteArray(chunkStates);
        dest.writeInt(framesTotal);
        dest.writeInt(framesUseful);
        dest.writeInt(duplicates);
        dest.writeDouble(percent);
        dest.writeLong(elapsedMs);
        dest.writeDouble(throughputBytesPerSecond);
        dest.writeDouble(overheadPercent);
        dest.writeInt(verified ? 1 : 0);
        dest.writeString(message);
        dest.writeString(recordUri);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Parcelable factory. */
    public static final Creator<TransferState> CREATOR = new Creator<TransferState>() {
        @Override
        public TransferState createFromParcel(Parcel in) {
            return new TransferState(in);
        }

        @Override
        public TransferState[] newArray(int size) {
            return new TransferState[size];
        }
    };
}
