package com.example.qrstream.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.qrstream.R;
import com.example.qrstream.provider.TransferContract.Transfers;
import com.example.qrstream.util.Formatters;

/**
 * Binds history rows to {@code item_transfer.xml} using the ViewHolder pattern: file name,
 * size, direction icon, status chip and date.
 */
public class TransferCursorAdapter extends CursorAdapter {

    private final LayoutInflater inflater;

    /** Cached views of one row, so findViewById runs once per row view. */
    private static final class ViewHolder {
        final ImageView direction;
        final TextView name;
        final TextView details;
        final TextView status;
        final ImageView synced;

        ViewHolder(View root) {
            direction = root.findViewById(R.id.image_direction);
            name = root.findViewById(R.id.text_name);
            details = root.findViewById(R.id.text_details);
            status = root.findViewById(R.id.text_status);
            synced = root.findViewById(R.id.image_synced);
        }
    }

    /**
     * @param context any context
     * @param cursor  initial cursor, may be null
     */
    public TransferCursorAdapter(Context context, Cursor cursor) {
        super(context, cursor, 0);
        inflater = LayoutInflater.from(context);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View view = inflater.inflate(R.layout.item_transfer, parent, false);
        view.setTag(new ViewHolder(view));
        return view;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        ViewHolder holder = (ViewHolder) view.getTag();
        String name = cursor.getString(cursor.getColumnIndexOrThrow(Transfers.FILE_NAME));
        long size = cursor.getLong(cursor.getColumnIndexOrThrow(Transfers.FILE_SIZE));
        String direction = cursor.getString(cursor.getColumnIndexOrThrow(Transfers.DIRECTION));
        String status = cursor.getString(cursor.getColumnIndexOrThrow(Transfers.STATUS));
        long created = cursor.getLong(cursor.getColumnIndexOrThrow(Transfers.CREATED_AT));
        boolean synced = cursor.getInt(cursor.getColumnIndexOrThrow(Transfers.SYNCED)) == 1;

        boolean sent = Transfers.DIRECTION_SENT.equals(direction);
        holder.name.setText(name);
        holder.details.setText(context.getString(R.string.history_row_details,
                Formatters.formatSize(size), Formatters.formatDate(created)));
        holder.direction.setImageResource(sent ? R.drawable.ic_sent : R.drawable.ic_received);
        holder.direction.setContentDescription(context.getString(
                sent ? R.string.direction_sent : R.string.direction_received));

        int labelRes;
        int colorRes;
        if (Transfers.STATUS_COMPLETED.equals(status)) {
            labelRes = R.string.status_completed;
            colorRes = R.color.status_completed;
        } else if (Transfers.STATUS_FAILED.equals(status)) {
            labelRes = R.string.status_failed;
            colorRes = R.color.status_failed;
        } else {
            labelRes = R.string.status_interrupted;
            colorRes = R.color.status_interrupted;
        }
        holder.status.setText(labelRes);
        ViewCompat.setBackgroundTintList(holder.status,
                ColorStateList.valueOf(ContextCompat.getColor(context, colorRes)));
        holder.synced.setVisibility(synced ? View.VISIBLE : View.GONE);
    }
}
