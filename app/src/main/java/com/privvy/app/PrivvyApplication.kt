package com.privvy.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.privvy.app.core.EngineState
import com.privvy.app.core.VirtualStorage
import com.privvy.app.engine.GuestReceiverRegistrar
import com.privvy.app.engine.VirtualEngine
import com.privvy.app.io.PrivvyNative
import com.privvy.app.security.SessionLockManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            if (!SessionLockManager.ensureGuestAllowed(this)) {
                Log.w("PrivvyApp", "locked guest process $processName is exiting")
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            startGuestSessionWatchdog()
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
            if (!SessionLockManager.isLocked(this)) SessionLockManager.lockSession(this)
            val sessionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        SessionLockManager.ACTION_SESSION_STARTED -> GuestReceiverRegistrar.refresh(context)
                        SessionLockManager.ACTION_SESSION_LOCKED -> GuestReceiverRegistrar.unregisterAll(context)
                    }
                }
            }
            val sessionFilter = IntentFilter().apply {
                addAction(SessionLockManager.ACTION_SESSION_STARTED)
                addAction(SessionLockManager.ACTION_SESSION_LOCKED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                sessionReceiver,
                sessionFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // Host process must keep real filesystem access (APK import, app
            // metadata) and must not wrap the real Instrumentation.
            Log.i("PrivvyApp", "host process — IO/instrumentation hooks are guest-only")
        }
    }

    private fun startGuestSessionWatchdog() {
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "privvy-session-watchdog").apply { isDaemon = true }
        }
        watchdog.scheduleWithFixedDelay(
            {
                val allowed = runCatching { SessionLockManager.ensureGuestAllowed(this) }.getOrDefault(false)
                if (!allowed) {
                    Log.w("PrivvyApp", "guest session is no longer authorized")
                    EngineState.lock()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            },
            1L,
            1L,
            TimeUnit.SECONDS,
        )
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
