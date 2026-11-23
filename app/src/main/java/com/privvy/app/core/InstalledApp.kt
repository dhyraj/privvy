package com.privvy.app.core

import org.json.JSONArray
import org.json.JSONObject

/** One guest app installed into a specific virtual space. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val launchActivity: String,
    val launchTheme: Int,
    val apkPaths: List<String>,
    val nativeLibDir: String?,
    val iconPath: String?,
    val installedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("label", label)
        put("versionName", versionName ?: JSONObject.NULL)
        put("versionCode", versionCode)
        put("launchActivity", launchActivity)
        put("launchTheme", launchTheme)
        put("apkPaths", JSONArray(apkPaths))
        put("nativeLibDir", nativeLibDir ?: JSONObject.NULL)
        put("iconPath", iconPath ?: JSONObject.NULL)
        put("installedAt", installedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): InstalledApp? {
            val pkg = json.optString("packageName").takeIf { it.isNotEmpty() } ?: return null
            val launch = json.optString("launchActivity").takeIf { it.isNotEmpty() } ?: return null
            val pathsJson = json.optJSONArray("apkPaths") ?: return null
            val paths = buildList {
                for (i in 0 until pathsJson.length()) {
                    pathsJson.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            if (paths.isEmpty()) return null
            return InstalledApp(
                packageName = pkg,
                label = json.optString("label", pkg),
                versionName = json.optString("versionName").takeIf { it.isNotEmpty() },
                versionCode = json.optLong("versionCode", 0L),
                launchActivity = launch,
                launchTheme = json.optInt("launchTheme", 0),
                apkPaths = paths,
                nativeLibDir = json.optString("nativeLibDir").takeIf { it.isNotEmpty() },
                iconPath = json.optString("iconPath").takeIf { it.isNotEmpty() },
                installedAt = json.optLong("installedAt", 0L),
            )
        }
    }
}
