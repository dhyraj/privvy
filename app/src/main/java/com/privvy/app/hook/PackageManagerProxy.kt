package com.privvy.app.hook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import android.util.Base64
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.engine.GuestPermissionStore
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object PackageManagerProxy {

    fun install(context: android.content.Context) {
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/ApplicationPackageManager;",
            )
        }
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val field = activityThreadClass.getDeclaredField("sPackageManager").apply { isAccessible = true }
        val original = field.get(null) ?: return
        if (Proxy.isProxyClass(original.javaClass)) return
        val interfaces = original.javaClass.interfaces
        val proxy = Proxy.newProxyInstance(
            original.javaClass.classLoader,
            interfaces,
            Handler(context, original),
        )
        field.set(null, proxy)
        runCatching {
            context.packageManager.javaClass.getDeclaredField("mPM")
                .apply { isAccessible = true }
                .set(context.packageManager, proxy)
        }.onFailure { android.util.Log.w("PrivvyHook", "Could not update cached PackageManager", it) }
        android.util.Log.i("PrivvyHook", "IPackageManager proxy installed")
    }

    private class Handler(
        private val context: android.content.Context,
        private val original: Any,
    ) : InvocationHandler {

        private val spaceId = com.privvy.app.core.EngineState.spaceId()
        private val packageCache = HashMap<String, PackageInfo>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val values = args?.toList().orEmpty()
            if (method.name == "toString") return "PrivvyPackageManager"
            if (method.name == "hashCode") return System.identityHashCode(proxy)
            if (method.name == "equals") return proxy === values.firstOrNull()
            try {
                handled(method.name, values)?.let { return it }
                return method.invoke(original, *values.toTypedArray())
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

        private fun handled(name: String, args: List<Any?>): Any? {
            uidBased(name, args)?.let { return it }
            val app = findApp(args) ?: return null
            return when (name) {
                "getPackageInfo" -> packageInfo(app, flags(args))
                "getApplicationInfo" -> packageInfo(app, flags(args)).applicationInfo
                "getActivityInfo" -> componentInfo(app, component(args), ActivityInfo::class.java)
                "getServiceInfo" -> componentInfo(app, component(args), ServiceInfo::class.java)
                "getReceiverInfo" -> componentInfo(app, component(args), android.content.pm.ActivityInfo::class.java)
                "getProviderInfo" -> componentInfo(app, component(args), ProviderInfo::class.java)
                "getLaunchIntentForPackage" -> launchIntent(app)
                "getPackageLabel", "getApplicationLabel" -> app.label
                "getApplicationIcon", "getActivityIcon" -> appIcon(app)
                "getPackageUid" -> Process.myUid()
                "checkPermission" -> if (valuesContainGuestPackage(args, app)) {
                    permissionResult(args, app)
                } else null
                "checkSignatures" -> signatureResult(args, app)
                "getPackagesForUid" -> guestPackagesForUid()
                "resolveActivity", "resolveService" -> resolveComponent(app, args)
                "resolveContentProvider" -> resolveProvider(app, args)
                "queryIntentActivities", "queryIntentServices" -> queryComponents(app, args)
                else -> null
            }
        }

        private fun uidBased(name: String, args: List<Any?>): Any? {
            val uid = args.filterIsInstance<Int>().firstOrNull() ?: return null
            if (uid != Process.myUid()) return null
            return when (name) {
                "getPackagesForUid" -> guestPackagesForUid()
                "getNameForUid" -> GuestIdentity.currentPackage() ?: context.packageName
                "checkSignatures" -> PackageManager.SIGNATURE_MATCH
                "checkPermission" -> {
                    val permission = args.filterIsInstance<String>()
                        .firstOrNull { it.startsWith("android.permission.") } ?: return null
                    val packageName = GuestIdentity.currentPackage()
                        ?: args.filterIsInstance<String>().firstOrNull { it in guestPackageNames() }
                        ?: return null
                    GuestPermissionStore.checkSelfPermission(context, spaceId, packageName, permission)
                }
                else -> null
            }
        }

        private fun guestPackageNames(): Set<String> =
            SpaceAppRegistry.list(context, spaceId).mapTo(HashSet()) { it.packageName }

        private fun guestPackagesForUid(): Array<String> =
            (listOf(context.packageName) + guestPackageNames()).toTypedArray()

        private fun findApp(args: List<Any?>): InstalledApp? {
            val packageName = args.filterIsInstance<String>().firstOrNull { candidate ->
                candidate.contains('.') && SpaceAppRegistry.list(context, spaceId).any { it.packageName == candidate }
            } ?: args.filterIsInstance<ComponentName>().firstOrNull { component ->
                SpaceAppRegistry.list(context, spaceId).any { it.packageName == component.packageName }
            }?.packageName ?: return null
            return SpaceAppRegistry.list(context, spaceId).firstOrNull { it.packageName == packageName }
        }

        private fun packageInfo(app: InstalledApp, flags: Int): PackageInfo = synchronized(packageCache) {
            packageCache.getOrPut("${app.packageName}|$flags") {
                val signatureFlags = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
                val archiveFlags = if (flags and signatureFlags != 0) flags or signatureFlags else flags
                val info = context.packageManager.getPackageArchiveInfo(app.apkPaths.first(), archiveFlags)
                    ?: throw PackageManager.NameNotFoundException(app.packageName)
                info.applicationInfo?.apply {
                    sourceDir = app.apkPaths.first()
                    publicSourceDir = app.apkPaths.first()
                    nativeLibraryDir = app.nativeLibDir
                    className = app.applicationClassName
                    if (Build.VERSION.SDK_INT >= 21) splitSourceDirs = app.apkPaths.drop(1).toTypedArray()
                }
                if (archiveFlags and PackageManager.GET_SIGNATURES != 0 && app.signatureBase64.isNotEmpty()) {
                    @Suppress("DEPRECATION")
                    info.signatures = app.signatureBase64.map { Signature(Base64.decode(it, Base64.NO_WRAP)) }
                        .toTypedArray()
                }
                info
            }
        }

        private fun componentInfo(app: InstalledApp, name: ComponentName?, type: Class<*>): Any? {
            if (name == null || name.packageName != app.packageName) return null
            val info = packageInfo(app, allComponentFlags())
            val component = when (type) {
                ActivityInfo::class.java -> info.activities?.firstOrNull { it.name == name.className }
                ServiceInfo::class.java -> info.services?.firstOrNull { it.name == name.className }
                ProviderInfo::class.java -> info.providers?.firstOrNull { it.name == name.className }
                else -> info.receivers?.firstOrNull { it.name == name.className }
            }
            return component ?: throw PackageManager.NameNotFoundException(name.flattenToString())
        }

        private fun resolveComponent(app: InstalledApp, args: List<Any?>): ResolveInfo? {
            val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return null
            if (intent.component?.packageName != app.packageName && intent.`package` != app.packageName) return null
            val component = intent.component ?: ComponentName(app.packageName, app.launchActivity)
            val info = componentInfo(app, component, ActivityInfo::class.java) as? ActivityInfo ?: return null
            return ResolveInfo().apply { activityInfo = info }
        }

        private fun resolveProvider(app: InstalledApp, args: List<Any?>): ProviderInfo? {
            val authority = args.filterIsInstance<String>().firstOrNull { it.isNotEmpty() } ?: return null
            val info = packageInfo(app, PackageManager.GET_PROVIDERS)
            val provider = info.providers?.firstOrNull { it.authority == authority } ?: return null
            return provider
        }

        private fun queryComponents(app: InstalledApp, args: List<Any?>): List<ResolveInfo> {
            val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return emptyList()
            if (intent.component?.packageName != app.packageName && intent.`package` != app.packageName) return emptyList()
            val component = intent.component ?: ComponentName(app.packageName, app.launchActivity)
            val info = componentInfo(app, component, ActivityInfo::class.java) as? ActivityInfo ?: return emptyList()
            return listOf(ResolveInfo().apply { activityInfo = info })
        }

        private fun launchIntent(app: InstalledApp): Intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.launchActivity)
        }

        private fun appIcon(app: InstalledApp): android.graphics.drawable.Drawable? = runCatching {
            packageInfo(app, 0).applicationInfo?.loadIcon(context.packageManager)
        }.getOrNull()

        private fun permissionResult(args: List<Any?>, app: InstalledApp): Int {
            val permission = args.filterIsInstance<String>().firstOrNull { it.startsWith("android.permission.") }
                ?: return PackageManager.PERMISSION_DENIED
            return GuestPermissionStore.checkSelfPermission(context, spaceId, app.packageName, permission)
        }

        private fun signatureResult(args: List<Any?>, app: InstalledApp): Int {
            val other = args.filterIsInstance<String>().firstOrNull { it.contains('.') && it != context.packageName }
            if (other == app.packageName) return PackageManager.SIGNATURE_MATCH
            val otherApp = SpaceAppRegistry.list(context, spaceId).firstOrNull { it.packageName == other }
            return if (otherApp != null && otherApp.signatureBase64.toSet() == app.signatureBase64.toSet()) {
                PackageManager.SIGNATURE_MATCH
            } else {
                PackageManager.SIGNATURE_UNKNOWN_PACKAGE
            }
        }

        private fun valuesContainGuestPackage(args: List<Any?>, app: InstalledApp) = args.any { it == app.packageName }

        private fun component(args: List<Any?>): ComponentName? = args.filterIsInstance<ComponentName>().firstOrNull()
            ?: run {
                val packageName = args.filterIsInstance<String>().firstOrNull { it.contains('.') }
                val className = args.filterIsInstance<String>().firstOrNull { it.contains('.') && it != packageName }
                if (packageName != null && className != null) ComponentName(packageName, className) else null
            }

        private fun flags(args: List<Any?>): Int =
            args.filterIsInstance<Long>().firstOrNull()?.toInt()
                ?: args.filterIsInstance<Int>().firstOrNull()
                ?: 0

        private fun allComponentFlags(): Int = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
    }
}
