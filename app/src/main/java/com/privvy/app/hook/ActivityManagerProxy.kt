package com.privvy.app.hook

import android.app.ActivityManager
import android.content.Context
import com.privvy.app.core.EngineState
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Guest-process IActivityManager mediation.
 *
 * The kernel calling identity remains Privvy's UID. This proxy only narrows
 * what a guest can observe or control: process/task lists are filtered to the
 * current space and destructive process-control calls are denied.
 */
object ActivityManagerProxy {

    fun install(context: Context) {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/ActivityManager;",
                "Landroid/app/IActivityManager;",
                "Landroid/util/Singleton;",
                "Landroid/content/pm/ParceledListSlice;",
            )
            val managerClass = ActivityManager::class.java
            val singletonField = managerClass.getDeclaredField("IActivityManagerSingleton")
                .apply { isAccessible = true }
            val singleton = singletonField.get(null) ?: return
            val instanceField = Class.forName("android.util.Singleton")
                .getDeclaredField("mInstance").apply { isAccessible = true }
            var original = instanceField.get(singleton)
            if (original == null) {
                original = runCatching {
                    managerClass.getDeclaredMethod("getService").invoke(null)
                }.getOrNull() ?: instanceField.get(singleton)
            }
            if (original == null || Proxy.isProxyClass(original.javaClass)) return
            val proxy = Proxy.newProxyInstance(
                original.javaClass.classLoader,
                original.javaClass.interfaces,
                Handler(context, original),
            )
            instanceField.set(singleton, proxy)
            android.util.Log.i("PrivvyHook", "IActivityManager proxy installed")
        }.onFailure {
            android.util.Log.w("PrivvyHook", "ActivityManager mediation unavailable", it)
        }
    }

    private class Handler(
        private val context: Context,
        private val original: Any,
    ) : InvocationHandler {

        private val spaceId = EngineState.spaceId()
        private val currentProcess = android.app.Application.getProcessName() ?: context.packageName

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "PrivvyActivityManager"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            if (GuestBinderPolicy.isDeniedProcessControl(method.name)) {
                throw SecurityException("Guest cannot control host processes: ${method.name}")
            }
            val values = args?.toList().orEmpty().toTypedArray()
            return try {
                when (method.name) {
                    "getRunningAppProcesses", "getProcessesInImportance" -> {
                        val result = method.invoke(original, *values) as? List<*> ?: return null
                        result.filterIsInstance<ActivityManager.RunningAppProcessInfo>()
                            .filter {
                                GuestBinderPolicy.isVisibleProcess(
                                    it.processName,
                                    currentProcess,
                                    spaceId,
                                    context.packageName,
                                )
                            }
                    }
                    "getRunningServices", "getRunningTasks", "getRecentTasks", "getAppTasks" -> emptyList<Any>()
                    "getHistoricalProcessExitReasons" -> emptyParceledList()
                        ?: method.invoke(original, *values)
                    else -> method.invoke(original, *values)
                }
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

        private fun emptyParceledList(): Any? = runCatching {
            Class.forName("android.content.pm.ParceledListSlice")
                .getConstructor(List::class.java)
                .newInstance(emptyList<Any>())
        }.getOrNull()
    }
}