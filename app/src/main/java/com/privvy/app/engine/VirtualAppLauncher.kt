package com.privvy.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.privvy.app.core.InstalledApp
import com.privvy.app.security.SessionLockManager

object VirtualAppLauncher {
    fun launch(context: Context, spaceId: Int, app: InstalledApp) {
        val hostContext = if (context is GuestContext) context.baseContext else context
        require(!SessionLockManager.isLocked(hostContext)) { "Privvy is locked" }
        require(spaceId == SessionLockManager.activeSpaceId(hostContext)) { "Space is not active" }
        val original = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.launchActivity)
        }
        hostContext.startActivity(
            GuestComponentRouter.activityIntent(hostContext, spaceId, app, original),
        )
    }
}
