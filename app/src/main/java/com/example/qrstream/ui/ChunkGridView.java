package com.example.qrstream.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.example.qrstream.R;

/**
 * Grid with one cell per chunk: grey = missing, green = received, blue = recovered by parity.
 *
 * <p>The cell size is chosen so that all chunks fit in the available area, from large cells
 * for a small file to tiny ones for the maximum of 1024 chunks. State codes match
 * {@code Reassembler.STATE_*}: 0 missing, 1 received, 2 recovered.</p>
 */
public class ChunkGridView extends View {

    private static final byte STATE_RECEIVED = 1;
    private static final byte STATE_RECOVERED = 2;
    private static final float MAX_CELL_DP = 28f;
    private static final float MIN_CELL_DP = 3f;
    private static final float GAP_DP = 1f;
    private static final float RADIUS_DP = 2f;
    private static final float SHRINK_STEP = 0.5f;

    private final Paint missingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint receivedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recoveredPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final float density;
    private byte[] states = new byte[0];

    /** @param context the context */
    public ChunkGridView(Context context) {
        this(context, null);
    }

    /**
     * @param context the context
     * @param attrs   XML attributes
     */
    public ChunkGridView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * @param context  the context
     * @param attrs    XML attributes
     * @param defStyle default style attribute
     */
    public ChunkGridView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        density = context.getResources().getDisplayMetrics().density;
        missingPaint.setColor(ContextCompat.getColor(context, R.color.cell_missing));
        receivedPaint.setColor(ContextCompat.getColor(context, R.color.cell_received));
        recoveredPaint.setColor(ContextCompat.getColor(context, R.color.cell_recovered));
    }

    /**
     * Replaces the displayed states and redraws.
     *
     * @param newStates one byte per chunk (0, 1 or 2), null for an empty grid
     */
    public void setStates(byte[] newStates) {
        states = newStates == null ? new byte[0] : newStates;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = states.length;
        if (n == 0) {
            return;
        }
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float width = getWidth() - left - getPaddingRight();
        float height = getHeight() - top - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        float maxCell = MAX_CELL_DP * density;
        float minCell = MIN_CELL_DP * density;
        float size = Math.min(maxCell, (float) Math.sqrt((double) width * height / n));
        int cols;
        int rows;
        while (true) {
            cols = Math.max(1, (int) (width / size));
            rows = (n + cols - 1) / cols;
            if (rows * size <= height || size <= minCell) {
                break;
            }
            size -= SHRINK_STEP;
        }
        float gap = GAP_DP * density;
        float radius = RADIUS_DP * density;
        for (int i = 0; i < n; i++) {
            int row = i / cols;
            int col = i % cols;
            cell.set(left + col * size + gap, top + row * size + gap,
                    left + (col + 1) * size - gap, top + (row + 1) * size - gap);
            Paint paint = states[i] == STATE_RECEIVED ? receivedPaint
                    : states[i] == STATE_RECOVERED ? recoveredPaint : missingPaint;
            canvas.drawRoundRect(cell, radius, radius, paint);
        }
    }
}
