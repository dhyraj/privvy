package com.privvy.app.engine

import android.content.Context
import android.content.Intent
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.stub.StubActivityStandard_V0
import com.privvy.app.core.stub.StubActivityStandard_V1
import com.privvy.app.core.stub.StubActivityStandard_V2
import com.privvy.app.core.stub.StubActivityStandard_V3
import com.privvy.app.core.stub.StubActivityBase

object VirtualAppLauncher {
    fun launch(context: Context, spaceId: Int, app: InstalledApp) {
        val stubClass = when (spaceId) {
            0 -> StubActivityStandard_V0::class.java
            1 -> StubActivityStandard_V1::class.java
            2 -> StubActivityStandard_V2::class.java
            else -> StubActivityStandard_V3::class.java
        }
        val original = Intent().apply {
            putExtra(GuestExtras.PACKAGE, app.packageName)
            putExtra(GuestExtras.ACTIVITY, app.launchActivity)
            putExtra(GuestExtras.APK_PATHS, app.apkPaths.toTypedArray())
            putExtra(GuestExtras.NATIVE_LIB_DIR, app.nativeLibDir)
            putExtra(GuestExtras.THEME, app.launchTheme)
            putExtra(GuestExtras.SPACE_ID, spaceId)
        }
        val stub = Intent(context, stubClass).apply {
            putExtra(StubActivityBase.EXTRA_ORIGINAL, original)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(stub)
    }
}
