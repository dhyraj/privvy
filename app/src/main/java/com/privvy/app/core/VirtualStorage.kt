package com.privvy.app.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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

    fun ensureSpace(context: Context, spaceId: Int) {
        require(spaceId in 0 until SPACE_SLOTS) { "Invalid space slot: $spaceId" }
        val root = root(context)
        val space = spaceDir(context, spaceId)
        val data = dataDir(context, spaceId)
        val sdcard = sdcardDir(context, spaceId)
        check(space.mkdirs() || space.isDirectory)
        check(data.mkdirs() || data.isDirectory)
        check(sdcard.mkdirs() || sdcard.isDirectory)
        check(space.canonicalFile.parentFile == root.canonicalFile)
        check(data.canonicalFile.parentFile == space.canonicalFile)
        check(sdcard.canonicalFile.parentFile == space.canonicalFile)
    }

    fun deleteTreeNoFollow(directory: File, expectedParent: File): Boolean {
        val path = directory.absoluteFile.toPath()
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        if (path.parent?.toFile()?.canonicalFile != expectedParent.canonicalFile) return false
        return try {
            Files.walkFileTree(path, object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: java.nio.file.Path, error: IOException): FileVisitResult {
                    throw error
                }

                override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (error: Exception) {
            false
        }
    }

    fun bootstrap(context: Context) {
        apksDir(context).mkdirs()
        repeat(SPACE_SLOTS) { id -> ensureSpace(context, id) }
    }
}
