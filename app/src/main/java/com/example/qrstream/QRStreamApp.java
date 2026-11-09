package com.example.qrstream;

import android.app.Application;

import com.example.qrstream.service.TransferService;
import com.example.qrstream.sync.SyncWorker;

/** Application class: creates notification channels and schedules a sync on every start. */
public class QRStreamApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        TransferService.createChannels(this);
        SyncWorker.enqueue(this);
    }
}
