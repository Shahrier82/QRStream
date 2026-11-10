package com.example.qrstream.ui;

import android.content.Context;

import com.example.qrstream.R;
import com.example.qrstream.service.TransferState;
import com.example.qrstream.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/** Turns a finished {@link TransferState} into the lines of the statistics panel. */
final class StatsPresenter {

    private StatsPresenter() {
    }

    /**
     * @param context any context, used for string resources
     * @param s       the final state of a transfer
     * @return multi-line text, one statistic per line
     */
    static String format(Context context, TransferState s) {
        List<String> lines = new ArrayList<>();
        if (s.mode == TransferState.MODE_SENDING) {
            lines.add(context.getString(R.string.stats_sent_frames, s.framesTotal, s.loopLength));
            lines.add(context.getString(R.string.stats_passes, s.passes));
            lines.add(context.getString(R.string.stats_dropped_sim, s.droppedBySimulation));
            lines.add(context.getString(R.string.stats_time, Formatters.formatDuration(s.elapsedMs)));
            lines.add(context.getString(R.string.stats_throughput_first_pass,
                    Formatters.formatSpeed(s.throughputBytesPerSecond)));
            lines.add(context.getString(R.string.stats_overhead_sender,
                    Formatters.formatPercent(Math.round(s.overheadPercent))));
        } else {
            lines.add(context.getString(R.string.stats_rx_frames, s.framesTotal, s.totalChunks));
            lines.add(context.getString(R.string.stats_duplicates, s.duplicates));
            lines.add(context.getString(R.string.stats_recovered, s.recoveredCount));
            lines.add(context.getString(R.string.stats_rejected, s.rejectedFrames));
            lines.add(context.getString(R.string.stats_time, Formatters.formatDuration(s.elapsedMs)));
            lines.add(context.getString(R.string.stats_throughput,
                    Formatters.formatSpeed(s.throughputBytesPerSecond)));
            lines.add(context.getString(R.string.stats_overhead_receiver,
                    Formatters.formatPercent(Math.round(s.overheadPercent))));
            if (s.status == TransferState.STATUS_COMPLETED) {
                lines.add(context.getString(R.string.stats_integrity_ok));
            } else if (s.status == TransferState.STATUS_FAILED && s.percent >= 100.0) {
                lines.add(context.getString(R.string.stats_integrity_bad));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
