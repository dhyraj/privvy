package com.privvy.guesttest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class SecondActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Second guest activity\npackage=" + getPackageName());
        text.setTextSize(24);
        setContentView(text);
    }
}
