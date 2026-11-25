package com.privvy.app.hook

import android.app.NotificationManager
import android.content.Context
import com.privvy.app.core.EngineState
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.security.SessionLockManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Guest-process INotificationManager mediation.
 *
 * Notifications are suppressed while the session is locked and guest package
 * arguments are rewritten to the host package because system_server only
 * accepts the calling UID's real package. This is not a separate notification
 * identity; it is a rootless best-effort guard.
 */
object NotificationProxy {

    fun install(context: Context) {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/NotificationManager;",
                "Landroid/app/INotificationManager;",
                "Landroid/content/pm/ParceledListSlice;",
            )
            val managerClass = NotificationManager::class.java
            val field = managerClass.getDeclaredField("sService").apply { isAccessible = true }
            var original = field.get(null)
            if (original == null) {
                original = runCatching {
                    managerClass.getDeclaredMethod("getService").apply { isAccessible = true }.invoke(null)
                }.getOrNull()
            }
            if (original == null || Proxy.isProxyClass(original.javaClass)) return
            val proxy = Proxy.newProxyInstance(
                original.javaClass.classLoader,
                original.javaClass.interfaces,
                Handler(context, original),
            )
            field.set(null, proxy)
            android.util.Log.i("PrivvyHook", "INotificationManager proxy installed")
        }.onFailure {
            android.util.Log.w("PrivvyHook", "Notification mediation unavailable", it)
        }
    }

    private class Handler(
        private val context: Context,
        private val original: Any,
    ) : InvocationHandler {

        private val spaceId = EngineState.spaceId()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "PrivvyNotificationManager"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            val values = args?.toMutableList() ?: mutableListOf()
            val locked = SessionLockManager.isLocked(context) || EngineState.isLocked
            if (method.name.startsWith("enqueue") && GuestBinderPolicy.suppressNotification(locked)) {
                return null
            }
            if (method.name in historicalNotificationQueries) {
                emptyParceledList()?.let { return it }
            }
            val guestPackages = SpaceAppRegistry.list(context, spaceId).mapTo(HashSet()) { it.packageName }
            for (index in values.indices) {
                val value = values[index]
                if (value is String) {
                    values[index] = GuestBinderPolicy.rewritePackage(value, guestPackages, context.packageName)
                }
            }
            return try {
                method.invoke(original, *values.toTypedArray())
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

        private fun emptyParceledList(): Any? = runCatching {
            Class.forName("android.content.pm.ParceledListSlice")
                .getConstructor(List::class.java)
                .newInstance(emptyList<Any>())
        }.getOrNull()

        private val historicalNotificationQueries = setOf(
            "getAppActiveNotifications",
            "getActiveNotifications",
            "getHistoricalNotifications",
            "getSnoozedNotifications",
        )
    }
}