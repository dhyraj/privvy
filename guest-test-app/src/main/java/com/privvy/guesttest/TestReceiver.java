package com.privvy.guesttest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileWriter;

public class TestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), "receiver.txt"))) {
            writer.write("guest-receiver-ran");
        } catch (Throwable ignored) {
        }
    }
}
