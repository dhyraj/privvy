package com.privvy.app.engine

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.view.WindowManager
import java.util.WeakHashMap
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.core.stub.StubActivityBase
import com.privvy.app.security.SessionLockManager

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
        val runtime: GuestRuntime,
        val themeId: Int,
    )

    private val guestStates = WeakHashMap<Activity, GuestState>()
    private val guestApps = WeakHashMap<Activity, GuestRuntime>()
    private val permissionDialogs = WeakHashMap<Activity, AlertDialog>()
    private val hostDeclaredPermissions by lazy {
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions.orEmpty().toSet()
        }.getOrDefault(emptySet())
    }

    init {
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/Instrumentation;",
                "Landroid/app/Activity;",
            )
        }
    }

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
            val app = SpaceAppRegistry.list(context, SessionLockManager.activeSpaceId(context))
                .firstOrNull { it.packageName == pkg }
                ?: throw IllegalStateException("Guest $pkg is not installed in this space")
            val runtime = GuestRuntimeFactory.get(context, app)
            val guest = runtime.loader.loadClass(activityName)
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Activity

            GuestResources.attach(guest, runtime.resources, themeId)
            guestStates[guest] = GuestState(runtime, themeId)
            guestApps[guest] = runtime

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

    // Hidden from the SDK classpath, but Activity launches still dispatch to this JVM signature.
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ): Instrumentation.ActivityResult? {
        val runtime = target?.let { guestApps[it] }
        if (runtime == null) {
            return invokeOriginalStart(who, contextThread, token, target, intent, requestCode, options)
        }
        val routed = routeGuestActivity(runtime, intent) ?: intent
        return invokeOriginalStart(context, contextThread, token, target, routed, requestCode, options)
    }

    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        resultWho: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ): Instrumentation.ActivityResult? {
        val runtime = (who as? Activity)?.let { guestApps[it] }
        if (runtime == null) {
            return invokeOriginalStart(who, resultWho, contextThread, token, intent, requestCode, options)
        }
        if (intent.action == "android.content.pm.action.REQUEST_PERMISSIONS") {
            return requestGuestPermissions(
                who as Activity,
                runtime,
                intent,
                resultWho,
                contextThread,
                token,
                requestCode,
                options,
            )
        }
        val routed = routeGuestActivity(runtime, intent) ?: intent
        return invokeOriginalStart(context, resultWho, contextThread, token, routed, requestCode, options)
    }

    private fun requestGuestPermissions(
        activity: Activity,
        runtime: GuestRuntime,
        intent: Intent,
        resultWho: String?,
        contextThread: IBinder,
        token: IBinder?,
        requestCode: Int,
        options: Bundle?,
    ): Instrumentation.ActivityResult? {
        val permissions = intent.getStringArrayExtra(REQUEST_PERMISSIONS_NAMES).orEmpty()
        val supported = permissions.filter { permission ->
            permission in runtime.app.requestedPermissions && permission in hostDeclaredPermissions
        }.distinct()
        val decisions = permissions.associateWith { false }.toMutableMap()
        supported.forEach { decisions[it] = GuestPermissionStore.isGranted(context, runtime.spaceId, runtime.app.packageName, it) }
        val needsDecision = supported.filter { decisions[it] != true }

        fun finish(granted: Boolean) {
            activity.window.decorView.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val hostMissing = supported.filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (granted && hostMissing.isNotEmpty()) {
                    val hostIntent = Intent(intent).apply {
                        putExtra(REQUEST_PERMISSIONS_NAMES, supported.toTypedArray())
                        putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                    }
                    invokeOriginalStart(context, resultWho, contextThread, token, hostIntent, requestCode, options)
                    return@post
                }
                setField(activity, "mHasCurrentPermissionsRequest", false)
                val results = IntArray(permissions.size) { index ->
                    if (granted && decisions[permissions[index]] == true &&
                        context.checkSelfPermission(permissions[index]) == PackageManager.PERMISSION_GRANTED
                    ) {
                        PackageManager.PERMISSION_GRANTED
                    } else {
                        PackageManager.PERMISSION_DENIED
                    }
                }
                activity.onRequestPermissionsResult(requestCode, permissions, results)
            }
        }

        if (needsDecision.isEmpty()) {
            finish(true)
            return null
        }

        var answered = false
        fun answerPermissionDialog(granted: Boolean) {
            if (answered) return
            answered = true
            supported.forEach { permission ->
                decisions[permission] = granted
                GuestPermissionStore.setGranted(
                    context,
                    runtime.spaceId,
                    runtime.app.packageName,
                    permission,
                    granted,
                )
            }
            if (!activity.isFinishing && !activity.isDestroyed) finish(granted)
        }

        val labels = needsDecision.joinToString("\n") { permission ->
            "• ${permissionLabel(permission)}"
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Allow ${runtime.app.label}?")
            .setMessage("${runtime.app.label} requests these permissions in this private space:\n\n$labels")
            .setPositiveButton("Allow", null)
            .setNegativeButton("Deny", null)
            .setOnCancelListener {
                answerPermissionDialog(false)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                answerPermissionDialog(true)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                answerPermissionDialog(false)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            answerPermissionDialog(false)
            permissionDialogs.remove(activity)
        }
        permissionDialogs[activity] = dialog
        dialog.show()
        return null
    }

    private fun permissionLabel(permission: String): String = runCatching {
        context.packageManager.getPermissionInfo(permission, 0)
            .loadLabel(context.packageManager).toString()
    }.getOrDefault(permission.substringAfterLast('.'))

    fun execStartActivities(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intents: Array<Intent>,
        options: Bundle?,
    ) {
        val runtime = target?.let { guestApps[it] }
        if (runtime == null) {
            invokeOriginalStarts(who, contextThread, token, target, intents, options)
            return
        }
        val routed = intents.map { routeGuestActivity(runtime, it) ?: it }.toTypedArray()
        invokeOriginalStarts(context, contextThread, token, target, routed, options)
    }

    private fun routeGuestActivity(runtime: GuestRuntime, intent: Intent): Intent? {
        val guestTarget = intent.component?.packageName == runtime.app.packageName ||
            intent.`package` == runtime.app.packageName
        if (!guestTarget) return null
        return GuestComponentRouter.activityIntent(
            context,
            runtime.spaceId,
            runtime.app,
            intent,
        ).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
    }

    private fun invokeOriginalStart(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ): Instrumentation.ActivityResult? = try {
        val method = original.javaClass.getMethod(
            "execStartActivity",
            Context::class.java,
            IBinder::class.java,
            IBinder::class.java,
            Activity::class.java,
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
        )
        method.invoke(original, who, contextThread, token, target, intent, requestCode, options)
            as? Instrumentation.ActivityResult
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw error.targetException
    }

    private fun invokeOriginalStart(
        who: Context,
        resultWho: String?,
        contextThread: IBinder,
        token: IBinder?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ): Instrumentation.ActivityResult? = try {
        val method = original.javaClass.getMethod(
            "execStartActivity",
            Context::class.java,
            IBinder::class.java,
            IBinder::class.java,
            String::class.java,
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
        )
        method.invoke(original, who, contextThread, token, resultWho, intent, requestCode, options)
            as? Instrumentation.ActivityResult
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw error.targetException
    }

    private fun invokeOriginalStarts(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intents: Array<Intent>,
        options: Bundle?,
    ) {
        try {
            val method = original.javaClass.getMethod(
                "execStartActivities",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Array<Intent>::class.java,
                Bundle::class.java,
            )
            method.invoke(original, who, contextThread, token, target, intents, options)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException
        }
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
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        original.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
    ) {
        applyGuestContext(activity)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        original.callActivityOnCreate(activity, icicle, persistentState)
    }

    fun callActivityOnRequestPermissionsResult(
        activity: Activity,
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        val runtime = guestApps[activity]
        if (runtime != null) {
            permissions.forEachIndexed { index, permission ->
                if (permission in runtime.app.requestedPermissions) {
                    val granted = grantResults.getOrNull(index) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    GuestPermissionStore.setGranted(context, runtime.spaceId, runtime.app.packageName, permission, granted)
                }
            }
        }
        activity.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
        val guestContext = GuestContext(base, state.runtime)
        setField(activity, "mBase", guestContext)
        setField(activity, "mResources", state.runtime.resources)
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
    override fun callActivityOnDestroy(activity: Activity) {
        permissionDialogs.remove(activity)?.dismiss()
        original.callActivityOnDestroy(activity)
    }
    override fun callActivityOnUserLeaving(activity: Activity) =
        original.callActivityOnUserLeaving(activity)

    override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle) =
        original.callActivityOnSaveInstanceState(activity, outState)

    override fun callActivityOnSaveInstanceState(
        activity: Activity,
        outState: Bundle,
        outPersistentState: PersistableBundle,
    ) = original.callActivityOnSaveInstanceState(activity, outState, outPersistentState)

    internal companion object {
        const val REQUEST_PERMISSIONS_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"
    }
}
