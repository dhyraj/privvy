package com.privvy.app.engine

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import com.privvy.app.core.VirtualStorage
import com.privvy.app.media.VirtualContentResolver
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

class GuestContext(
    base: Context,
    private val runtime: GuestRuntime,
) : ContextWrapper(base) {

    private val guestInflater: LayoutInflater by lazy {
        LayoutInflater.from(base).cloneInContext(this)
    }

    private val mediaResolver: ContentResolver by lazy {
        VirtualContentResolver(base, runtime.spaceId, runtime.app.packageName, runtime.app.providerAuthorities.toSet())
    }

    private val preferences = HashMap<String, GuestSharedPreferences>()
    private val preferenceListeners = HashMap<String, MutableSet<SharedPreferences.OnSharedPreferenceChangeListener>>()
    private val guestTheme: Resources.Theme by lazy { resources.newTheme() }

    override fun getResources(): Resources = runtime.resources

    override fun getAssets(): AssetManager = runtime.resources.assets

    override fun getClassLoader(): ClassLoader = runtime.loader

    override fun getPackageName(): String = runtime.app.packageName

    override fun getOpPackageName(): String = runtime.app.packageName

    override fun getApplicationContext(): Context = runtime.application ?: this

    override fun getTheme(): Resources.Theme = guestTheme

    override fun getSystemService(name: String): Any? =
        if (name == Context.LAYOUT_INFLATER_SERVICE) guestInflater else super.getSystemService(name)

    override fun getContentResolver(): ContentResolver = mediaResolver

    override fun getApplicationInfo(): ApplicationInfo =
        baseContext.packageManager.getPackageArchiveInfo(runtime.app.apkPaths.first(), 0)?.applicationInfo
            ?: super.getApplicationInfo()

    override fun getPackageResourcePath(): String = runtime.app.apkPaths.first()

    override fun getPackageCodePath(): String = runtime.app.apkPaths.first()

    override fun getDataDir(): File = File("/data/user/0/${runtime.app.packageName}")

    override fun getFilesDir(): File = File(dataDir, "files").apply { mkdirs() }

    override fun getCacheDir(): File = File(dataDir, "cache").apply { mkdirs() }

    override fun getCodeCacheDir(): File = File(dataDir, "code_cache").apply { mkdirs() }

    override fun getNoBackupFilesDir(): File = File(dataDir, "no_backup").apply { mkdirs() }

    override fun getDir(name: String?, mode: Int): File {
        val safeName = name?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: "app"
        return File(dataDir, safeName).apply { mkdirs() }
    }

    override fun getDatabasePath(name: String?): File = File(dataDir, "databases/$name")

    override fun getFileStreamPath(name: String?): File = File(filesDir, name.orEmpty())

    override fun openFileInput(name: String?): FileInputStream = FileInputStream(getFileStreamPath(name))

    override fun openFileOutput(name: String?, mode: Int): FileOutputStream =
        FileOutputStream(getFileStreamPath(name), mode and MODE_APPEND != 0)

    override fun deleteFile(name: String?): Boolean = getFileStreamPath(name).delete()

    override fun fileList(): Array<String> = filesDir.list() ?: arrayOf()

    override fun getExternalFilesDir(type: String?): File? {
        val typePath = type?.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
        return File(
            VirtualStorage.sdcardDir(baseContext, runtime.spaceId),
            "Android/data/${runtime.app.packageName}/files$typePath",
        ).apply { mkdirs() }
    }

    override fun getExternalFilesDirs(type: String?): Array<File> =
        listOfNotNull(getExternalFilesDir(type)).toTypedArray()

    override fun getExternalCacheDir(): File? =
        File(VirtualStorage.sdcardDir(baseContext, runtime.spaceId), "Android/data/${runtime.app.packageName}/cache")
            .apply { mkdirs() }

    override fun getExternalCacheDirs(): Array<File> = listOfNotNull(externalCacheDir).toTypedArray()

    override fun getExternalMediaDirs(): Array<File> = arrayOf(
        File(VirtualStorage.sdcardDir(baseContext, runtime.spaceId), "Android/media/${runtime.app.packageName}")
            .apply { mkdirs() },
    )

    override fun getObbDir(): File =
        File(VirtualStorage.sdcardDir(baseContext, runtime.spaceId), "Android/obb/${runtime.app.packageName}")
            .apply { mkdirs() }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val preferencesName = name ?: "default"
        return synchronized(preferences) {
            preferences.getOrPut(preferencesName) {
                val safeName = preferencesName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val file = File(
                    VirtualStorage.dataDir(baseContext, runtime.spaceId),
                    "${runtime.app.packageName}/shared_prefs/$safeName.json",
                )
                GuestSharedPreferences(file, preferenceListeners.getOrPut(preferencesName) { mutableSetOf() })
            }
        }
    }

    override fun checkSelfPermission(permission: String): Int =
        GuestPermissionStore.checkSelfPermission(baseContext, runtime.spaceId, runtime.app.packageName, permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = checkSelfPermission(permission)

    override fun startActivity(intent: Intent?) {
        if (isGuestIntent(intent)) {
            baseContext.startActivity(
                GuestComponentRouter.activityIntent(baseContext, runtime.spaceId, runtime.app, requireNotNull(intent)),
            )
        } else {
            super.startActivity(intent)
        }
    }

    override fun startActivity(intent: Intent?, options: android.os.Bundle?) {
        if (isGuestIntent(intent)) {
            baseContext.startActivity(
                GuestComponentRouter.activityIntent(baseContext, runtime.spaceId, runtime.app, requireNotNull(intent)),
                options,
            )
        } else {
            super.startActivity(intent, options)
        }
    }

    override fun startActivities(intents: Array<out Intent>?) {
        val mapped = intents?.map { intent ->
            if (isGuestIntent(intent)) {
                GuestComponentRouter.activityIntent(baseContext, runtime.spaceId, runtime.app, intent)
            } else intent
        }?.toTypedArray()
        if (mapped.isNullOrEmpty()) super.startActivities(intents) else baseContext.startActivities(mapped)
    }

    override fun startActivities(intents: Array<out Intent>?, options: android.os.Bundle?) {
        val mapped = intents?.map { intent ->
            if (isGuestIntent(intent)) {
                GuestComponentRouter.activityIntent(baseContext, runtime.spaceId, runtime.app, intent)
            } else intent
        }?.toTypedArray()
        if (mapped.isNullOrEmpty()) super.startActivities(intents, options) else baseContext.startActivities(mapped, options)
    }

    override fun startService(intent: Intent?): ComponentName? {
        require(intent?.component?.packageName != baseContext.packageName) { "Guest cannot control host services" }
        if (!isGuestIntent(intent)) return super.startService(intent)
        val original = requireNotNull(intent)
        val result = baseContext.startService(
            GuestComponentRouter.serviceIntent(baseContext, runtime.spaceId, runtime.app, original),
        )
        return original.component?.let { ComponentName(runtime.app.packageName, it.className) }
            ?: result?.takeIf { it.packageName == baseContext.packageName }?.let { null }
    }

    override fun stopService(intent: Intent?): Boolean {
        require(intent?.component?.packageName != baseContext.packageName) { "Guest cannot control host services" }
        if (!isGuestIntent(intent)) return super.stopService(intent)
        return baseContext.stopService(
            GuestComponentRouter.serviceIntent(baseContext, runtime.spaceId, runtime.app, requireNotNull(intent)),
        )
    }

    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        require(intent.component?.packageName != baseContext.packageName) { "Guest cannot control host services" }
        if (!isGuestIntent(intent)) return super.bindService(intent, connection, flags)
        return baseContext.bindService(
            GuestComponentRouter.serviceIntent(baseContext, runtime.spaceId, runtime.app, intent),
            connection,
            flags,
        )
    }

    override fun sendBroadcast(intent: Intent?) {
        if (isGuestReceiverIntent(intent)) {
            baseContext.sendBroadcast(
                GuestComponentRouter.receiverIntent(baseContext, runtime.spaceId, runtime.app, requireNotNull(intent)),
            )
        } else {
            super.sendBroadcast(intent)
        }
    }

    override fun sendOrderedBroadcast(intent: Intent?, receiverPermission: String?) {
        if (isGuestReceiverIntent(intent)) {
            baseContext.sendOrderedBroadcast(
                GuestComponentRouter.receiverIntent(baseContext, runtime.spaceId, runtime.app, requireNotNull(intent)),
                receiverPermission,
            )
        } else {
            super.sendOrderedBroadcast(intent, receiverPermission)
        }
    }

    private fun isGuestIntent(intent: Intent?): Boolean {
        val component = intent?.component
        return component?.packageName == runtime.app.packageName || intent?.`package` == runtime.app.packageName
    }

    private fun isGuestReceiverIntent(intent: Intent?): Boolean {
        val component = intent?.component ?: return false
        return component.packageName == runtime.app.packageName && runtime.app.receiverNames.contains(component.className)
    }
}
