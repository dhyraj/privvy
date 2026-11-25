package com.privvy.app.engine

import android.content.Context
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.SpaceAppRegistry

object GuestEngineState {
    fun installedApp(packageName: String, context: Context? = null): InstalledApp? {
        val appContext = context ?: com.privvy.app.core.EngineState.context()
        return SpaceAppRegistry.list(appContext, com.privvy.app.core.EngineState.spaceId())
            .firstOrNull { it.packageName == packageName }
    }
}
