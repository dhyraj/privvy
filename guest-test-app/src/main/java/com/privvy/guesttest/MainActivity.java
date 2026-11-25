package com.privvy.guesttest;

import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.io.FileWriter;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private TextView storageText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView guestText = findViewById(R.id.guest_text);
        guestText.setText(getString(R.string.guest_message));

        TextView packageText = findViewById(R.id.package_text);
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            packageText.setText("package=" + getPackageName() + "\npm=" + info.packageName
                    + "\nsignatures=" + info.signatures.length);
        } catch (Throwable error) {
            packageText.setText("package=" + getPackageName() + "\nPM FAIL: " + error);
        }

        storageText = findViewById(R.id.storage_text);
        storageText.setText(storageSummary());

        findViewById(R.id.write_button).setOnClickListener(v -> writeStorageTest());
        findViewById(R.id.prefs_button).setOnClickListener(v -> testPreferences());
        findViewById(R.id.activity_button).setOnClickListener(v ->
                startActivity(new Intent(this, SecondActivity.class)));
        findViewById(R.id.service_button).setOnClickListener(v ->
                startService(new Intent(this, TestService.class)));
        findViewById(R.id.receiver_button).setOnClickListener(v -> {
            sendBroadcast(new Intent(this, TestReceiver.class));
            storageText.setText("Receiver dispatched");
        });
        findViewById(R.id.media_button).setOnClickListener(v -> testMediaStore());
        findViewById(R.id.provider_button).setOnClickListener(v -> testProvider());
    }

    private void writeStorageTest() {
        File file = new File("/sdcard/guest-boot-test.txt");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("guest-wrote-this");
        } catch (Throwable error) {
            storageText.setText("FAIL: " + error);
            return;
        }
        storageText.setText("PASS: wrote /sdcard/guest-boot-test.txt (exists=" + file.exists() + ")");
    }

    private String storageSummary() {
        SharedPreferences preferences = getSharedPreferences("guest", 0);
        return "Application=" + preferences.getString("application", "missing")
                + "\nservice=" + (new File(getFilesDir(), "service.txt").exists() ? "created" : "missing")
                + "\nreceiver=" + (new File(getFilesDir(), "receiver.txt").exists() ? "received" : "missing")
                + "\ndata=" + getFilesDir();
    }

    private void testPreferences() {
        boolean written = getSharedPreferences("guest", 0).edit().putString("probe", "isolated").commit();
        String value = getSharedPreferences("guest", 0).getString("probe", "missing");
        storageText.setText("PASS prefs=" + written + " value=" + value + "\n" + storageSummary());
    }

    private void testProvider() {
        Uri collection = Uri.parse("content://com.privvy.guesttest.provider/items");
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (String value : new String[]{"one", "two"}) {
            operations.add(ContentProviderOperation.newInsert(collection)
                    .withValue("value", value).build());
        }
        try {
            getContentResolver().applyBatch("com.privvy.guesttest.provider", operations);
            Bundle result = getContentResolver().call(
                    "com.privvy.guesttest.provider", "ping", null, new Bundle());
            storageText.setText(result.getString("result", "FAIL provider call"));
        } catch (Throwable error) {
            error.printStackTrace();
            storageText.setText("FAIL provider: " + error);
        }
    }

    private void testMediaStore() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "guest-image-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/PrivvyGuest");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            storageText.setText("FAIL MediaStore insert");
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("null output stream");
            output.write(new byte[]{1, 2, 3, 4});
        } catch (Throwable error) {
            storageText.setText("FAIL MediaStore write: " + error);
            return;
        }
        String path = "missing";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.DATA}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) path = cursor.getString(0);
        }
        storageText.setText("PASS MediaStore=" + uri + "\npath=" + path);
    }
}
