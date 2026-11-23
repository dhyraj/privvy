package com.privvy.app

import android.app.Application
import android.util.Log
import com.privvy.app.core.EngineState
import com.privvy.app.core.VirtualStorage
import com.privvy.app.engine.VirtualEngine
import com.privvy.app.io.PrivvyNative

class PrivvyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        EngineState.setContext(this)

        // Create virtual/apks and virtual/user_0..N/{data,sdcard} before any
        // hook can redirect a guest open() into them.
        VirtualStorage.bootstrap(this)
        Log.i("PrivvyApp", "virtual storage bootstrapped at ${VirtualStorage.root(this)}")

        val processName = getProcessName() ?: packageName
        val isGuestProcess = processName != packageName

        if (isGuestProcess) {
            // Guest process (:v0..): install IO hooks for this space and hook
            // instrumentation so stub launches become guest activities.
            if (PrivvyNative.isLoaded) {
                PrivvyNative.init(applicationInfo.dataDir)
                PrivvyNative.setActiveSpace(EngineState.spaceId())
                PrivvyNative.installFileHooks()
                Log.i("PrivvyApp", "guest process=$processName — IO hooks installed")
                if (BuildConfig.DEBUG) {
                    runTranslationSelfTest()
                }
            } else {
                Log.e("PrivvyApp", "libprivvyio.so not loaded — guest IO is NOT isolated")
            }
            VirtualEngine.hookInstrumentation(this)
        } else {
            // Host process must keep real filesystem access (APK import, app
            // metadata) and must not wrap the real Instrumentation.
            Log.i("PrivvyApp", "host process — IO/instrumentation hooks are guest-only")
        }
    }

    private fun runTranslationSelfTest() {
        val cases = listOf(
            "/data/data/com.example.guest/cache/foo.txt",
            "/data/user/0/com.example.guest/databases/bar.db",
            "/sdcard/DCIM/Camera/img.jpg",
            "/storage/emulated/0/Download/report.pdf",
            applicationInfo.dataDir + "/files/keystore_meta/x", // must pass through
        )
        for (p in cases) {
            Log.i("PrivvyIO-Test", "$p  ->  ${PrivvyNative.translatePath(p)}")
        }

        try {
            val guestPath = "/data/data/com.example.guest/selftest.txt"
            val landed = PrivvyNative.probeOpen(guestPath)
            Log.i("PrivvyIO-Test", "probeOpen($guestPath) -> landed at: $landed")
            if (!landed.startsWith("OPEN_FAILED")) {
                val f = java.io.File(landed)
                Log.i("PrivvyIO-Test", "landed file exists=${f.exists()} size=${f.length()} content='${f.readText()}'")
            }
        } catch (t: Throwable) {
            Log.e("PrivvyIO-Test", "self-test failed", t)
        }
    }
}
