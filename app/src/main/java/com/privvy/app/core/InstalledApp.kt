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
    val applicationClassName: String? = null,
    val requestedPermissions: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val serviceNames: List<String> = emptyList(),
    val receiverNames: List<String> = emptyList(),
    val providerNames: List<String> = emptyList(),
    val providerAuthorities: List<String> = emptyList(),
    val signatureBase64: List<String> = emptyList(),
    val targetSdk: Int = 0,
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
        put("applicationClassName", applicationClassName ?: JSONObject.NULL)
        put("requestedPermissions", JSONArray(requestedPermissions))
        put("activityNames", JSONArray(activityNames))
        put("serviceNames", JSONArray(serviceNames))
        put("receiverNames", JSONArray(receiverNames))
        put("providerNames", JSONArray(providerNames))
        put("providerAuthorities", JSONArray(providerAuthorities))
        put("signatureBase64", JSONArray(signatureBase64))
        put("targetSdk", targetSdk)
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
                applicationClassName = json.optString("applicationClassName").takeIf { it.isNotEmpty() },
                requestedPermissions = json.stringList("requestedPermissions"),
                activityNames = json.stringList("activityNames"),
                serviceNames = json.stringList("serviceNames"),
                receiverNames = json.stringList("receiverNames"),
                providerNames = json.stringList("providerNames"),
                providerAuthorities = json.stringList("providerAuthorities"),
                signatureBase64 = json.stringList("signatureBase64"),
                targetSdk = json.optInt("targetSdk", 0),
            )
        }

        private fun JSONObject.stringList(name: String): List<String> {
            val array = optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
    }
}
