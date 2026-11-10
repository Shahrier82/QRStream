package com.example.qrstream.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * An ImageView that is as tall as it is wide, so the QR code is always square. The size is
 * capped at 80 percent of the screen height, so in landscape the QR code still fits on screen.
 */
public class SquareImageView extends AppCompatImageView {

    private static final float MAX_SCREEN_HEIGHT_FRACTION = 0.8f;

    /** @param context the context */
    public SquareImageView(Context context) {
        super(context);
    }

    /**
     * @param context the context
     * @param attrs   XML attributes
     */
    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * @param context  the context
     * @param attrs    XML attributes
     * @param defStyle default style attribute
     */
    public SquareImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * MAX_SCREEN_HEIGHT_FRACTION);
        int side = Math.min(width, cap);
        setMeasuredDimension(side, side);
    }
}
