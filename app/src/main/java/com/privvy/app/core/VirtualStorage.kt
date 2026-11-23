package com.privvy.app.core

import android.content.Context
import java.io.File

/**
 * Owns the virtual container disk layout (spec §6):
 *
 *   virtual/
 *   ├── apks/<guest_pkg>/          base.apk + split APKs (shared across spaces)
 *   └── user_<id>/
 *       ├── data/<guest_pkg>/      redirected /data/data/<guest_pkg>/
 *       └── sdcard/                redirected /sdcard/
 *
 * The native hook translates paths into this tree, but it does not create
 * parent directories — each space's roots must exist before any guest open()
 * with O_CREAT can succeed.
 */
object VirtualStorage {

    /** Matches the manifest stub pool (:v0..:v3). */
    const val SPACE_SLOTS = 4

    fun root(context: Context): File =
        File(context.applicationInfo.dataDir, "virtual")

    fun apksDir(context: Context): File = File(root(context), "apks")

    fun appApkDir(context: Context, packageName: String): File =
        File(apksDir(context), packageName)

    /** Debug-only staging area for test APKs (files/import). */
    fun importStagingDir(context: Context): File =
        File(context.filesDir, "import")

    fun spaceDir(context: Context, spaceId: Int): File =
        File(root(context), "user_$spaceId")

    fun dataDir(context: Context, spaceId: Int): File =
        File(spaceDir(context, spaceId), "data")

    fun sdcardDir(context: Context, spaceId: Int): File =
        File(spaceDir(context, spaceId), "sdcard")

    /** Idempotently create apks/ and user_0..user_N/{data,sdcard}. */
    fun bootstrap(context: Context) {
        apksDir(context).mkdirs()
        repeat(SPACE_SLOTS) { id ->
            dataDir(context, id).mkdirs()
            sdcardDir(context, id).mkdirs()
        }
    }
}
