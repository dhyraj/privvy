package com.privvy.app.engine

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import com.privvy.app.core.EngineState
import com.privvy.app.core.InstalledApp
import com.privvy.app.hook.GuestIdentity
import dalvik.system.DexClassLoader

data class GuestRuntime(
    val context: Context,
    val app: InstalledApp,
    val loader: DexClassLoader,
    val resources: Resources,
    var application: Application?,
) {
    val spaceId: Int = EngineState.spaceId()
    val guestContext: GuestContext by lazy { GuestContext(context, this) }
}

object GuestRuntimeFactory {

    private val runtimes = HashMap<String, GuestRuntime>()

    @Synchronized
    fun get(context: Context, app: InstalledApp): GuestRuntime {
        GuestIdentity.setProcessPackage(app.packageName)
        runtimes[app.packageName]?.let { return it }
        val loader = VirtualEngine.guestClassLoader(context, app.packageName, app.apkPaths, app.nativeLibDir)
        val resources = GuestResources.build(context, app.apkPaths)
        val runtime = GuestRuntime(context, app, loader, resources, null)
        runtime.application = createApplication(runtime)
        return runtime.also { runtimes[app.packageName] = it }
    }

    fun createService(context: Context, app: InstalledApp, className: String): Service {
        GuestIdentity.setProcessPackage(app.packageName)
        val runtime = get(context, app)
        val service = runtime.loader.loadClass(className).getDeclaredConstructor().newInstance() as Service
        attach(service, context as Service, runtime)
        service.onCreate()
        return service
    }

    private fun createApplication(runtime: GuestRuntime): Application? {
        val name = runtime.app.applicationClassName ?: return null
        return runCatching {
            val application = runtime.loader.loadClass(name).getDeclaredConstructor().newInstance() as Application
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
                .invoke(application, runtime.guestContext)
            setField(application, "mBase", runtime.guestContext)
            runtime.application = application
            application.onCreate()
            application
        }.getOrElse { error ->
            android.util.Log.e("PrivvyGuest", "guest Application failed for ${runtime.app.packageName}", error)
            null
        }
    }

    private fun attach(service: Service, stub: Service, runtime: GuestRuntime) {
        setField(service, "mBase", runtime.guestContext)
        setField(service, "mResources", runtime.resources)
        setField(service, "mApplication", runtime.application ?: runtime.context.applicationContext as Application)
        listOf(
            "mToken",
            "mActivityThread",
            "mActivityManager",
            "mClassName",
            "mPackageName",
            "mStartCompatibility",
        ).forEach { name ->
            runCatching { field(stub, name)?.let { source -> field(service, name)?.set(service, source.get(stub)) } }
        }
    }

    private fun field(target: Any, name: String): java.lang.reflect.Field? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            runCatching { return type.getDeclaredField(name).apply { isAccessible = true } }
            type = type.superclass
        }
        return null
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
                .onSuccess { return }
            type = type.superclass
        }
    }

    fun clear(packageName: String) {
        runtimes.remove(packageName)
    }

    /** Clears every cached guest runtime after guest processes stop. */
    @Synchronized
    fun clearAll() {
        runtimes.clear()
    }

}
