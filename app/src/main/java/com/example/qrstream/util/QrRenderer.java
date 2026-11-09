package com.example.qrstream.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.example.qrstream.protocol.ProtocolConstants;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Turns frame text into a QR code bitmap with ZXing. Call from a background thread. */
public final class QrRenderer {

    private QrRenderer() {
    }

    /**
     * Renders black modules on a white background with a 4-module quiet zone.
     *
     * @param text     frame text (URL-safe Base64)
     * @param sizePx   edge length in pixels, raised to at least 600
     * @param strongEc true for error correction level M, false for L
     * @return a square bitmap
     * @throws WriterException if the text does not fit in any QR version at that level
     */
    public static Bitmap render(String text, int sizePx, boolean strongEc) throws WriterException {
        int size = Math.max(sizePx, ProtocolConstants.QR_SIZE_PX);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION,
                strongEc ? ErrorCorrectionLevel.M : ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, ProtocolConstants.QR_QUIET_ZONE_MODULES);
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
    }
}
