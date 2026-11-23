package com.privvy.app.core

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Per-space installation registry, persisted at
 * `virtual/user_<id>/apps.json`.
 *
 * The APK bytes live once under `virtual/apks/<pkg>/`; this file decides which
 * spaces can see/launch the app. Two spaces may list the same package with
 * completely separate data directories.
 */
object SpaceAppRegistry {

    @Synchronized
    fun list(context: Context, spaceId: Int): List<InstalledApp> {
        val file = appsFile(context, spaceId)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    InstalledApp.fromJson(obj)?.let(::add)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("PrivvyRegistry", "failed to read ${file.absolutePath}", t)
            emptyList()
        }
    }

    @Synchronized
    fun upsert(context: Context, spaceId: Int, app: InstalledApp): List<InstalledApp> {
        val apps = list(context, spaceId).filterNot { it.packageName == app.packageName } + app
        save(context, spaceId, apps)
        return apps
    }

    @Synchronized
    fun remove(context: Context, spaceId: Int, packageName: String): List<InstalledApp> {
        val apps = list(context, spaceId).filterNot { it.packageName == packageName }
        save(context, spaceId, apps)
        return apps
    }

    fun appsFile(context: Context, spaceId: Int): File =
        File(VirtualStorage.spaceDir(context, spaceId), "apps.json")

    private fun save(context: Context, spaceId: Int, apps: List<InstalledApp>) {
        val file = appsFile(context, spaceId)
        file.parentFile?.mkdirs()
        val array = JSONArray()
        apps.forEach { array.put(it.toJson()) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(array.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString(2))
            tmp.delete()
        }
    }
}
