package com.privvy.guesttest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;

/**
 * Minimal guest fixture for the Privvy engine.
 *
 * It deliberately exercises the three things Milestone 1 must prove:
 *  1. Guest classloading (this class is not in Privvy's APK).
 *  2. Guest resource injection (@layout, @string).
 *  3. Guest path redirection (/sdcard write must land in virtual/user_0).
 */
public class MainActivity extends Activity {

    private TextView storageText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView guestText = findViewById(R.id.guest_text);
        guestText.setText(getString(R.string.guest_message));

        TextView packageText = findViewById(R.id.package_text);
        packageText.setText("package=" + getPackageName());

        storageText = findViewById(R.id.storage_text);
        storageText.setText("No storage test run yet");

        Button writeButton = findViewById(R.id.write_button);
        writeButton.setOnClickListener(v -> writeStorageTest());
    }

    private void writeStorageTest() {
        File file = new File("/sdcard/guest-boot-test.txt");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("guest-wrote-this");
        } catch (Throwable t) {
            storageText.setText("FAIL: " + t);
            return;
        }
        storageText.setText(
                "PASS: wrote /sdcard/guest-boot-test.txt (exists=" + file.exists() + ")"
        );
    }
}
