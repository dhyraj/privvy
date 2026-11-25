package com.privvy.guesttest;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.File;
import java.io.FileWriter;

public class TestService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), "service.txt"))) {
            writer.write("guest-service-ran");
        } catch (Throwable ignored) {
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
