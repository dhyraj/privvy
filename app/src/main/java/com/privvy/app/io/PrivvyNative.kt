package com.privvy.app.io

/**
 * Kotlin → libprivvyio.so bridge (Layer 2).
 * Symbols are bound via RegisterNatives in JNI_OnLoad (privvyio.cpp).
 * WARNING: the JVM method names below must stay in sync with the
 * JNINativeMethod table in privvyio.cpp.
 */
object PrivvyNative {

    @JvmStatic
    external fun init(privvyDataDir: String)

    @JvmStatic
    external fun setActiveSpace(spaceId: Int)

    @JvmStatic
    external fun translatePath(path: String): String

    @JvmStatic
    external fun probeOpen(path: String): String

    @JvmStatic
    external fun installFileHooks()

    val isLoaded: Boolean = try {
        System.loadLibrary("privvyio")
        true
    } catch (e: UnsatisfiedLinkError) {
        android.util.Log.e("PrivvyIO", "libprivvyio.so failed to load", e)
        false
    }
}
