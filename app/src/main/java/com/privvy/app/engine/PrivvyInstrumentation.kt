package com.privvy.app.engine

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle

/**
 * PrivvyInstrumentation — replaces ActivityThread.mInstrumentation in :v* guest
 * processes ONLY (never the host process, which would break instrumentation
 * tests and normal host lifecycle behavior).
 *
 * When system_server launches a StubActivity, the original Intent (carrying the
 * guest package/class + APK path) is attached under
 * [com.privvy.app.core.stub.StubActivityBase.EXTRA_ORIGINAL]. [newActivity]
 * instantiates the guest Activity class from a per-APK DexClassLoader.
 *
 * Every lifecycle method is delegated to the original Instrumentation —
 * otherwise activities never receive onCreate/onResume, which manifests as a
 * blank screen or hanging UI tests.
 */
class PrivvyInstrumentation(
    private val original: Instrumentation,
    private val context: Context,
) : Instrumentation() {

    // ------------------------------------------------------------------
    // Guest instantiation
    // ------------------------------------------------------------------
    override fun newActivity(cl: ClassLoader?, className: String, intent: Intent?): Activity {
        val originalIntent = intent?.getParcelableExtra<Intent>(
            com.privvy.app.core.stub.StubActivityBase.EXTRA_ORIGINAL
        )
        val guestClass = originalIntent?.getStringExtra("privvy.guest_class")
        val apkPath = originalIntent?.getStringExtra("privvy.apk_path")
        if (guestClass != null && apkPath != null) {
            val loader = guestClassLoaderFor(apkPath)
            return loader.loadClass(guestClass).getDeclaredConstructor().newInstance() as Activity
        }
        return original.newActivity(cl, className, intent)
    }

    private val classLoaders = mutableMapOf<String, ClassLoader>()

    private fun guestClassLoaderFor(apkPath: String): ClassLoader =
        classLoaders.getOrPut(apkPath) {
            val outDir = context.codeCacheDir.resolve("privvy-guest")
            VirtualEngine.buildDexClassLoader(
                apkPaths = apkPath.split(":"),
                nativeLibDir = outDir,
                parentClassLoader = context.classLoader,
            )
        }

    // ------------------------------------------------------------------
    // Lifecycle delegation — REQUIRED. Instrumentation's defaults are no-ops.
    // ------------------------------------------------------------------
    override fun onCreate(arguments: Bundle?) = original.onCreate(arguments)
    override fun onStart() = original.onStart()
    override fun onException(obj: Any?, e: Throwable) = original.onException(obj, e)

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = original.newApplication(cl, className, context)

    override fun callApplicationOnCreate(app: Application?) =
        original.callApplicationOnCreate(app)

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) =
        original.callActivityOnCreate(activity, icicle)

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
    ) = original.callActivityOnCreate(activity, icicle, persistentState)

    override fun callActivityOnPostCreate(activity: Activity, icicle: Bundle?) =
        original.callActivityOnPostCreate(activity, icicle)

    override fun callActivityOnPostCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
    ) = original.callActivityOnPostCreate(activity, icicle, persistentState)

    override fun callActivityOnRestoreInstanceState(activity: Activity, savedInstanceState: Bundle) =
        original.callActivityOnRestoreInstanceState(activity, savedInstanceState)

    override fun callActivityOnRestoreInstanceState(
        activity: Activity,
        savedInstanceState: Bundle?,
        persistentState: PersistableBundle?,
    ) = original.callActivityOnRestoreInstanceState(activity, savedInstanceState, persistentState)

    override fun callActivityOnNewIntent(activity: Activity, intent: Intent?) =
        original.callActivityOnNewIntent(activity, intent)

    override fun callActivityOnStart(activity: Activity) = original.callActivityOnStart(activity)
    override fun callActivityOnRestart(activity: Activity) = original.callActivityOnRestart(activity)
    override fun callActivityOnResume(activity: Activity) = original.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = original.callActivityOnPause(activity)
    override fun callActivityOnStop(activity: Activity) = original.callActivityOnStop(activity)
    override fun callActivityOnDestroy(activity: Activity) = original.callActivityOnDestroy(activity)
    override fun callActivityOnUserLeaving(activity: Activity) =
        original.callActivityOnUserLeaving(activity)

    override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle) =
        original.callActivityOnSaveInstanceState(activity, outState)

    override fun callActivityOnSaveInstanceState(
        activity: Activity,
        outState: Bundle,
        outPersistentState: PersistableBundle,
    ) = original.callActivityOnSaveInstanceState(activity, outState, outPersistentState)
}
