package com.privvy.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import com.privvy.app.core.VirtualStorage
import org.json.JSONObject
import java.io.File

object GuestPermissionStore {

    private val lock = Any()
    private const val PROTECTION_MASK = 0x0f

    fun isGranted(context: Context, spaceId: Int, packageName: String, permission: String): Boolean = synchronized(lock) {
        val file = file(context, spaceId, packageName)
        val stored = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null
        if (stored?.has(permission) == true) return stored.optBoolean(permission)
        if (!isRuntimePermission(context, permission)) return true
        false
    }

    fun setGranted(context: Context, spaceId: Int, packageName: String, permission: String, granted: Boolean) = synchronized(lock) {
        val file = file(context, spaceId, packageName)
        file.parentFile?.mkdirs()
        val json = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } else JSONObject()
        json.put(permission, granted)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(json.toString())
            temporary.delete()
        }
    }

    fun checkSelfPermission(context: Context, spaceId: Int, packageName: String, permission: String): Int =
        if (isGranted(context, spaceId, packageName, permission) &&
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        ) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED

    private fun file(context: Context, spaceId: Int, packageName: String): File {
        val encoded = Base64.encodeToString(packageName.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        return File(VirtualStorage.spaceDir(context, spaceId), "permissions/$encoded.json")
    }

    private fun isRuntimePermission(context: Context, permission: String): Boolean = runCatching {
        val info = context.packageManager.getPermissionInfo(permission, 0)
        (info.protectionLevel and PROTECTION_MASK) == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(true)
}
