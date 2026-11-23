package com.privvy.app.core

import android.app.Application
import android.content.Context

/**
 * Global engine state consulted by the hook layer in every :v* process.
 *
 * The hook layer (com.privvy.app.hook) needs three facts at all times:
 *  1. Which virtual space this process belongs to (0..N).
 *  2. Which guest package is mapped onto which stub component.
 *  3. Whether the session is locked (FR-08) — locked sessions must refuse
 *     to serve guest traffic.
 */
object EngineState {

    @Volatile
    var isLocked: Boolean = true
        private set

    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun context(): Context = requireNotNull(appContext) { "EngineState not initialized" }

    /**
     * Space id for the current process, derived from its ":vN" suffix.
     * Mirrors the manifest stub pool (:v0..:v15).
     */
    fun spaceId(): Int {
        val processName = Application.getProcessName()
            ?: context().packageName
        return processSpaceId(processName)
    }

    fun processSpaceId(processName: String): Int {
        val idx = processName.lastIndexOf(":v")
        if (idx >= 0) {
            processName.substring(idx + 2).toIntOrNull()?.let { return it }
        }
        return 0
    }

    fun lock() {
        isLocked = true
    }

    fun unlock() {
        isLocked = false
    }
}
