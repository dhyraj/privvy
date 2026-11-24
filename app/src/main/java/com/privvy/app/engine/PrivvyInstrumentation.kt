package com.privvy.app.engine

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.PersistableBundle
import java.util.WeakHashMap
import com.privvy.app.core.stub.StubActivityBase

/**
 * PrivvyInstrumentation — replaces ActivityThread.mInstrumentation in :v* guest
 * processes ONLY (never the host process, which would break instrumentation
 * tests and normal host lifecycle behavior).
 *
 * When system_server launches a StubActivity, the original Intent (carrying the
 * guest package/class + APK path) is attached under
 * [com.privvy.app.core.stub.StubActivityBase.EXTRA_ORIGINAL]. [newActivity]
 * instantiates the guest Activity class from a per-APK DexClassLoader and
 * injects guest Resources before returning it.
 *
 * Every lifecycle method is delegated to the original Instrumentation —
 * otherwise activities never receive onCreate/onResume, which manifests as a
 * blank screen or hanging UI tests.
 */
class PrivvyInstrumentation(
    private val original: Instrumentation,
    private val context: Context,
) : Instrumentation() {

    private data class GuestState(
        val packageName: String,
        val resources: Resources,
        val themeId: Int,
    )

    private val guestStates = WeakHashMap<Activity, GuestState>()

    // ------------------------------------------------------------------
    // Guest instantiation
    // ------------------------------------------------------------------
    override fun newActivity(cl: ClassLoader?, className: String, intent: Intent?): Activity {
        val originalIntent = intent?.getParcelableExtra<Intent>(StubActivityBase.EXTRA_ORIGINAL)
        val pkg = originalIntent?.getStringExtra(GuestExtras.PACKAGE)
        val activityName = originalIntent?.getStringExtra(GuestExtras.ACTIVITY)
        val apkPaths = originalIntent?.getStringArrayExtra(GuestExtras.APK_PATHS)?.toList()
        val nativeLibDir = originalIntent?.getStringExtra(GuestExtras.NATIVE_LIB_DIR)
        val themeId = originalIntent?.getIntExtra(GuestExtras.THEME, 0) ?: 0

        if (pkg != null && activityName != null && !apkPaths.isNullOrEmpty()) {
            val loader = VirtualEngine.guestClassLoader(context, pkg, apkPaths, nativeLibDir)
            val guest = loader.loadClass(activityName)
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Activity

            val resources = GuestResources.build(context, apkPaths)
            GuestResources.attach(guest, resources, themeId)
            guestStates[guest] = GuestState(pkg, resources, themeId)

            // Rewrite the stub intent in place so Activity.attach sees the guest
            // component and the framework reports the guest package/name.
            intent?.component = ComponentName(pkg, activityName)
            intent?.action = Intent.ACTION_MAIN
            intent?.addCategory(Intent.CATEGORY_LAUNCHER)
            intent?.removeExtra(StubActivityBase.EXTRA_ORIGINAL)
            intent?.removeExtra(GuestExtras.PACKAGE)
            intent?.removeExtra(GuestExtras.ACTIVITY)
            intent?.removeExtra(GuestExtras.APK_PATHS)
            intent?.removeExtra(GuestExtras.NATIVE_LIB_DIR)
            intent?.removeExtra(GuestExtras.THEME)
            intent?.removeExtra(GuestExtras.SPACE_ID)

            return guest
        }
        return original.newActivity(cl, className, intent)
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

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        applyGuestContext(activity)
        original.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
    ) {
        applyGuestContext(activity)
        original.callActivityOnCreate(activity, icicle, persistentState)
    }

    /**
     * Activity.attach has run by now, so mBase exists. Replace it with a
     * guest-scoped Context before the guest onCreate runs: this makes
     * LayoutInflater resolve bundled guest libraries through the guest
     * DexClassLoader and names the package correctly.
     */
    private fun applyGuestContext(activity: Activity) {
        val state = guestStates.remove(activity) ?: return
        val base = activity.baseContext ?: return
        val guestContext = GuestContext(
            base = base,
            guestResources = state.resources,
            // Keep the host package for now: runtime permission requests are
            // validated against the calling UID. Guest package spoofing needs
            // the Binder permission proxy (Milestone 2), otherwise
            // requestPermissions() throws SecurityException. Identity is still
            // isolated via the guest class loader and resources.
            guestPackageName = base.packageName,
            guestClassLoader = activity.javaClass.classLoader,
        )
        setField(activity, "mBase", guestContext)
        setField(activity, "mResources", state.resources)
        if (state.themeId != 0) {
            try { activity.setTheme(state.themeId) } catch (_: Throwable) { }
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        android.util.Log.w("PrivvyInstrumentation", "field $name not found on ${target.javaClass.name}")
    }

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
