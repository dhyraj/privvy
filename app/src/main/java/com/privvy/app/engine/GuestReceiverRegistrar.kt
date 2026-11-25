package com.privvy.app.engine

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import android.util.Log
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.security.SessionLockManager

/**
 * Host-side approximation of static manifest receiver registration.
 *
 * A rootless container cannot add real system PackageManager receiver records.
 * While a session is active, Privvy registers dynamic host receivers for the
 * guest manifest intent-filters it parsed at import time and forwards matching
 * broadcasts to the per-space stub receiver. Registration is removed on lock.
 */
object GuestReceiverRegistrar {

    private val registered = LinkedHashMap<String, BroadcastReceiver>()

    @Synchronized
    fun refresh(context: Context) {
        unregisterAll(context)
        if (Application.getProcessName() != context.packageName) return
        if (SessionLockManager.isLocked(context)) return
        val spaceId = SessionLockManager.activeSpaceId(context)
        SpaceAppRegistry.list(context, spaceId).forEach { app ->
            app.receiverFilters.forEachIndexed { index, filter ->
                if (filter.actions.isEmpty()) return@forEachIndexed
                val intentFilter = IntentFilter().apply {
                    filter.actions.forEach(::addAction)
                    filter.categories.forEach(::addCategory)
                    filter.dataSchemes.forEach(::addDataScheme)
                    filter.dataHosts.forEach { addDataAuthority(it, null) }
                    filter.dataPaths.forEach { addDataPath(it, PatternMatcher.PATTERN_LITERAL) }
                    filter.dataMimeTypes.forEach(::addDataType)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (SessionLockManager.isLocked(ctx)) return
                        val original = Intent(intent).apply {
                            component = ComponentName(app.packageName, filter.receiverName)
                            `package` = app.packageName
                        }
                        val stub = GuestComponentRouter.receiverIntent(ctx, spaceId, app, original)
                        ctx.sendBroadcast(stub)
                    }
                }
                val key = "$spaceId|${app.packageName}|${filter.receiverName}|$index"
                runCatching {
                    val flag = if (filter.exported) {
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                    } else {
                        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                    }
                    androidx.core.content.ContextCompat.registerReceiver(context, receiver, intentFilter, flag)
                    registered[key] = receiver
                }.onFailure {
                    Log.w("PrivvyReceiver", "failed to register ${filter.receiverName}", it)
                }
            }
        }
        Log.i("PrivvyReceiver", "registered ${registered.size} guest receiver filters for space $spaceId")
    }

    @Synchronized
    fun unregisterAll(context: Context) {
        registered.values.forEach { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        registered.clear()
    }
}