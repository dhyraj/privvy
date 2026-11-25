package com.privvy.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.stub.StubActivityBase

object GuestComponentRouter {

    fun decorate(original: Intent, app: InstalledApp, spaceId: Int): Intent = original.apply {
        putExtra(GuestExtras.PACKAGE, app.packageName)
        putExtra(GuestExtras.ACTIVITY, component?.className ?: app.launchActivity)
        putExtra(GuestExtras.APK_PATHS, app.apkPaths.toTypedArray())
        putExtra(GuestExtras.NATIVE_LIB_DIR, app.nativeLibDir)
        putExtra(GuestExtras.THEME, app.launchTheme)
        putExtra(GuestExtras.SPACE_ID, spaceId)
    }

    fun activityIntent(context: Context, spaceId: Int, app: InstalledApp, original: Intent): Intent {
        val guestIntent = Intent(original)
        val component = original.component
        if (component != null && component.packageName == app.packageName) {
            guestIntent.component = ComponentName(app.packageName, resolveClass(component.className, app.packageName))
        } else if (guestIntent.`package` == app.packageName || component == null) {
            guestIntent.component = ComponentName(app.packageName, app.launchActivity)
        }
        decorate(guestIntent, app, spaceId)
        return Intent(context, stubClass("StubActivityStandard", spaceId)).apply {
            putExtra(StubActivityBase.EXTRA_ORIGINAL, guestIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun serviceIntent(context: Context, spaceId: Int, app: InstalledApp, original: Intent): Intent {
        val guestIntent = Intent(original)
        val component = original.component
        if (component?.packageName == app.packageName) {
            guestIntent.component = ComponentName(app.packageName, resolveClass(component.className, app.packageName))
        }
        decorate(guestIntent, app, spaceId)
        return Intent(context, stubClass("StubService", spaceId)).apply {
            putExtra(StubActivityBase.EXTRA_ORIGINAL, guestIntent)
        }
    }

    fun receiverIntent(context: Context, spaceId: Int, app: InstalledApp, original: Intent): Intent {
        val guestIntent = Intent(original)
        val component = original.component
        if (component?.packageName == app.packageName) {
            guestIntent.component = ComponentName(app.packageName, resolveClass(component.className, app.packageName))
        }
        decorate(guestIntent, app, spaceId)
        return Intent(context, stubClass("StubReceiver", spaceId)).apply {
            putExtra(StubActivityBase.EXTRA_ORIGINAL, guestIntent)
        }
    }

    fun original(intent: Intent?): Intent? = intent?.getParcelableExtra<Intent>(StubActivityBase.EXTRA_ORIGINAL)

    fun appFrom(intent: Intent?): InstalledApp? {
        val original = original(intent) ?: return null
        val packageName = original.getStringExtra(GuestExtras.PACKAGE) ?: return null
        return GuestEngineState.installedApp(packageName)
    }

    fun resolveClass(name: String, packageName: String): String = when {
        name.startsWith(".") -> packageName + name
        !name.contains('.') -> "$packageName.$name"
        else -> name
    }

    private fun stubClass(prefix: String, spaceId: Int): Class<*> =
        Class.forName("com.privvy.app.core.stub.${prefix}_V$spaceId")
}
