package com.privvy.guesttest;

import android.app.Application;

public class TestApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        getSharedPreferences("guest", 0).edit().putString("application", "created").apply();
    }
}
