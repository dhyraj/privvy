package com.privvy.app.core

import android.graphics.Bitmap

/**
 * A virtual space (spec FR-03): one isolated sandbox of cloned apps,
 * storage, and sessions inside the host app's private directory.
 */
data class VirtualSpace(
    val id: Int,                    // user_0, user_1, ... (process slot :vN)
    val name: String,               // "Decoy", "Vault", user-assigned
    val createdAt: Long,
    val pinHashHex: String?,
    val pinSaltHex: String? = null,
    val isDecoy: Boolean = false,
) {
    /** Files/<privvy>/virtual/user_<id> — see spec §6. */
    fun dataRoot(): String = "virtual/user_$id/data"
    fun sdcardRoot(): String = "virtual/user_$id/sdcard"
}

/** One cloned/imported guest app inside a space (FR-04). */
data class GuestApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val icon: Bitmap?,
    val isSplitApk: Boolean,        // .apks/.xapk bundles need unified nativeLibraryDir
    val apkPaths: List<String>,     // base.apk + split_config.*.apk (spec §9)
)

/**
 * Space repository — Milestone 1 placeholder. Milestone 3 (FR-03) turns this
 * into create/rename/wipe/delete with Argon2id PIN storage under
 * files/keystore_meta/ (spec §6).
 */
interface SpaceRepository {
    fun listSpaces(): List<VirtualSpace>
    fun createSpace(name: String, pin: CharArray?): VirtualSpace
    fun renameSpace(id: Int, name: String): VirtualSpace
    fun setPin(id: Int, pin: String)
    fun verifyPin(id: Int, pin: String): Boolean
    fun findSpaceByPin(pin: String): VirtualSpace?
    fun deleteSpace(id: Int)
    fun wipeSpace(id: Int)
    /** Resets metadata to the single PIN-less default space. */
    fun resetToDefault(): VirtualSpace
}
