package com.example.qrstream.protocol;

/** Thrown when bytes or text cannot be decoded as a valid protocol frame or META payload. */
public class FrameFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable reason the data was rejected
     */
    public FrameFormatException(String message) {
        super(message);
    }

    /**
     * @param message human-readable reason the data was rejected
     * @param cause   underlying cause
     */
    public FrameFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
