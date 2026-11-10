package com.example.qrstream.ui;

import android.widget.SeekBar;

/** SeekBar listener that only cares about value changes. */
abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {

    /**
     * @param progress  new progress value
     * @param fromUser  true if the user moved the thumb, false if the code set the value
     */
    abstract void onChanged(int progress, boolean fromUser);

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        onChanged(progress, fromUser);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // Not needed.
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // Not needed.
    }
}
