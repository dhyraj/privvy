package com.privvy.app.media

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.privvy.app.core.VirtualStorage
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Host-side API for the per-space media replacement. Guest code normally uses
 * [VirtualContentResolver]; these helpers are also the provider implementation.
 */
object VirtualMediaStore {
    const val AUTHORITY = "com.privvy.app.virtualmedia"
    const val COLLECTION_MIME_TYPE = "vnd.android.cursor.dir/vnd.android.cursor.item"

    fun collectionUri(spaceId: Int): Uri {
        requireValidSpaceId(spaceId)
        return Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(spaceId.toString())
            .build()
    }

    fun uri(spaceId: Int, mediaId: Long): Uri {
        requireValidSpaceId(spaceId)
        requireValidMediaId(mediaId)
        return collectionUri(spaceId).buildUpon()
            .appendPath(mediaId.toString())
            .build()
    }

    fun insert(context: Context, spaceId: Int, values: ContentValues): Uri =
        insertInternal(context, spaceId, values, context.packageName, mediaType = null)

    @JvmOverloads
    fun query(
        context: Context,
        spaceId: Int,
        projection: Array<out String>? = null,
        selection: String? = null,
        selectionArgs: Array<out String>? = null,
        sortOrder: String? = null,
        mediaType: String? = null,
    ): Cursor = queryInternal(
        context = context,
        spaceId = spaceId,
        mediaId = null,
        projection = projection,
        selection = selection,
        selectionArgs = selectionArgs,
        sortOrder = sortOrder,
        mediaType = mediaType,
        limit = null,
        offset = 0,
    )

    fun openFile(context: Context, spaceId: Int, mediaId: Long, mode: String): ParcelFileDescriptor {
        requireValidSpaceId(spaceId)
        requireValidMediaId(mediaId)
        val store = MediaMetadataStore(context, spaceId)
        val entry = store.readEntries().firstOrNull { it.id == mediaId }
            ?: throw FileNotFoundException("Unknown virtual media id $mediaId in space $spaceId")
        val file = store.resolveFile(entry)
        if (!file.isFile) throw FileNotFoundException("Missing virtual media file: $file")
        return ParcelFileDescriptor.open(file, openMode(mode))
    }

    fun delete(context: Context, spaceId: Int, mediaId: Long): Int =
        deleteInternal(context, spaceId, mediaId, null, null, null)

    @JvmOverloads
    fun delete(
        context: Context,
        spaceId: Int,
        selection: String?,
        selectionArgs: Array<out String>?,
        mediaType: String? = null,
    ): Int = deleteInternal(context, spaceId, null, selection, selectionArgs, mediaType)

    fun update(
        context: Context,
        spaceId: Int,
        mediaId: Long,
        values: ContentValues,
    ): Int = updateInternal(context, spaceId, mediaId, values, null, null, null)

    @JvmOverloads
    fun update(
        context: Context,
        spaceId: Int,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<out String>?,
        mediaType: String? = null,
    ): Int = updateInternal(context, spaceId, null, values, selection, selectionArgs, mediaType)

    internal fun insertInternal(
        context: Context,
        spaceId: Int,
        values: ContentValues,
        ownerPackageName: String?,
        mediaType: String?,
    ): Uri {
        requireValidSpaceId(spaceId)
        val appContext = context.applicationContext ?: context
        val displayName = MediaMetadataStore.sanitizeDisplayName(
            values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                ?: values.getAsString("display_name")
                ?: throw IllegalArgumentException("DISPLAY_NAME is required"),
        )
        val mimeType = normalizeMimeType(values, displayName)
        val relativePath = normalizeRelativePath(values, mediaType, mimeType)
        val now = System.currentTimeMillis() / 1000L
        val width = integerValue(values, MediaStore.MediaColumns.WIDTH).coerceAtLeast(0)
        val height = integerValue(values, MediaStore.MediaColumns.HEIGHT).coerceAtLeast(0)
        val isPending = if (integerValue(values, MediaStore.MediaColumns.IS_PENDING) != 0) 1 else 0
        val dateAdded = positiveLongValue(values, MediaStore.MediaColumns.DATE_ADDED, now)
        val dateModified = positiveLongValue(values, MediaStore.MediaColumns.DATE_MODIFIED, now)
        val store = MediaMetadataStore(appContext, spaceId)
        val root = VirtualStorage.sdcardDir(appContext, spaceId)
        var created: File? = null

        val mediaId = try {
            store.mutate { state ->
                val parent = MediaMetadataStore.resolveFile(
                    root,
                    relativePath,
                    displayName,
                    createParents = true,
                ).parentFile ?: throw IOException("Missing media parent directory")
                val file = MediaMetadataStore.createUniqueFile(parent, displayName)
                created = file
                val id = state.nextId
                state.nextId++
                state.items.add(
                    MediaEntry(
                        id = id,
                        displayName = file.name,
                        mimeType = mimeType,
                        relativePath = relativePath,
                        size = file.length(),
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        width = width,
                        height = height,
                        isPending = isPending,
                        ownerPackageName = ownerPackageName?.takeIf { it.isNotBlank() },
                    ),
                )
                id
            }
        } catch (t: Throwable) {
            created?.delete()
            throw t
        }
        return uri(spaceId, mediaId)
    }

    internal fun queryInternal(
        context: Context,
        spaceId: Int,
        mediaId: Long?,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        mediaType: String?,
        limit: Int?,
        offset: Int,
    ): MatrixCursor {
        requireValidSpaceId(spaceId)
        mediaId?.let(::requireValidMediaId)
        val outputColumns = MediaQuery.validateProjection(projection)
        val filter = normalizeMediaTypeFilter(mediaType)
        val matcher = MediaQuery.selection(selection, selectionArgs)
        val rows = MediaMetadataStore(context, spaceId).readEntries()
            .asSequence()
            .filter { mediaId == null || it.id == mediaId }
            .filter { filter == null || mimeTypeMatches(it.mimeType, filter) }
            .map { MediaQuery.row(context, spaceId, it) }
            .filter(matcher)
            .toList()
        return MediaQuery.buildCursor(
            context = context,
            spaceId = spaceId,
            projection = outputColumns,
            rows = rows,
            sortOrder = sortOrder,
            limit = limit,
            offset = offset,
        )
    }

    internal fun deleteInternal(
        context: Context,
        spaceId: Int,
        mediaId: Long?,
        selection: String?,
        selectionArgs: Array<out String>?,
        mediaType: String?,
    ): Int {
        requireValidSpaceId(spaceId)
        mediaId?.let(::requireValidMediaId)
        val filter = normalizeMediaTypeFilter(mediaType)
        val matcher = MediaQuery.selection(selection, selectionArgs)
        val store = MediaMetadataStore(context, spaceId)
        val files = ArrayList<File>()
        val count = store.mutate { state ->
            val targets = state.items.filter { entry ->
                (mediaId == null || entry.id == mediaId) &&
                    (filter == null || mimeTypeMatches(entry.mimeType, filter)) &&
                    matcher(MediaQuery.row(context, spaceId, entry))
            }
            if (targets.isEmpty()) return@mutate 0
            // Validate every path before committing the metadata removal.
            files.addAll(targets.map { store.resolveFile(it) })
            state.items.removeAll(targets.toSet())
            files.size
        }
        // The metadata commit above makes deleted rows inaccessible even if an
        // orphan file has to be left behind after an I/O failure.
        files.forEach { file ->
            if (!file.delete() && file.exists()) {
                throw IOException("Media row deleted but file could not be removed: $file")
            }
        }
        return count
    }

    internal fun updateInternal(
        context: Context,
        spaceId: Int,
        mediaId: Long?,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<out String>?,
        mediaType: String?,
    ): Int {
        requireValidSpaceId(spaceId)
        mediaId?.let(::requireValidMediaId)
        if (values.size() == 0) return 0
        val filter = normalizeMediaTypeFilter(mediaType)
        val matcher = MediaQuery.selection(selection, selectionArgs)
        val store = MediaMetadataStore(context, spaceId)
        val root = VirtualStorage.sdcardDir(context.applicationContext ?: context, spaceId)
        val moves = mutableListOf<Move>()
        val now = System.currentTimeMillis() / 1000L

        return try {
            store.mutate { state ->
                val targets = state.items.filter { entry ->
                    (mediaId == null || entry.id == mediaId) &&
                        (filter == null || mimeTypeMatches(entry.mimeType, filter)) &&
                        matcher(MediaQuery.row(context, spaceId, entry))
                }
                if (targets.isEmpty()) return@mutate 0

                val replacements = LinkedHashMap<Long, MediaEntry>()
                targets.forEach { old ->
                    val oldFile = store.resolveFile(old)
                    if (!oldFile.isFile) throw FileNotFoundException("Missing media file: $oldFile")

                    val newDisplayName = if (values.containsKey(MediaStore.MediaColumns.DISPLAY_NAME) ||
                        values.containsKey("display_name")
                    ) {
                        MediaMetadataStore.sanitizeDisplayName(
                            values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                                ?: values.getAsString("display_name")
                                ?: throw IllegalArgumentException("DISPLAY_NAME cannot be null"),
                        )
                    } else {
                        old.displayName
                    }
                    val newRelativePath = if (values.containsKey(MediaStore.MediaColumns.RELATIVE_PATH)) {
                        normalizeRelativePath(values, null, old.mimeType)
                    } else {
                        old.relativePath
                    }
                    val newMimeType = if (values.containsKey(MediaStore.MediaColumns.MIME_TYPE)) {
                        normalizeMimeType(values, newDisplayName)
                    } else {
                        old.mimeType
                    }

                    var actualName = newDisplayName
                    var newFile = MediaMetadataStore.resolveFile(
                        root,
                        newRelativePath,
                        actualName,
                        createParents = true,
                    )
                    if (newFile.absolutePath != oldFile.absolutePath) {
                        repeat(64) {
                            if (Files.exists(newFile.toPath())) {
                                actualName = MediaMetadataStore.collisionName(newDisplayName)
                                newFile = MediaMetadataStore.resolveFile(
                                    root,
                                    newRelativePath,
                                    actualName,
                                    createParents = true,
                                )
                            }
                            try {
                                Files.move(
                                    oldFile.toPath(),
                                    newFile.toPath(),
                                    StandardCopyOption.ATOMIC_MOVE,
                                )
                                moves.add(Move(oldFile, newFile))
                                newFile.setLastModified(now * 1000L)
                                return@repeat
                            } catch (_: FileAlreadyExistsException) {
                                actualName = MediaMetadataStore.collisionName(newDisplayName)
                                newFile = MediaMetadataStore.resolveFile(
                                    root,
                                    newRelativePath,
                                    actualName,
                                    createParents = true,
                                )
                            }
                        }
                        if (moves.none { it.from == oldFile }) {
                            throw IOException("Unable to allocate a unique renamed media file")
                        }
                    }

                    replacements[old.id] = old.copy(
                        displayName = actualName,
                        mimeType = newMimeType,
                        relativePath = newRelativePath,
                        dateModified = now,
                        width = if (values.containsKey(MediaStore.MediaColumns.WIDTH)) {
                            integerValue(values, MediaStore.MediaColumns.WIDTH).coerceAtLeast(0)
                        } else old.width,
                        height = if (values.containsKey(MediaStore.MediaColumns.HEIGHT)) {
                            integerValue(values, MediaStore.MediaColumns.HEIGHT).coerceAtLeast(0)
                        } else old.height,
                        isPending = if (values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                            if (integerValue(values, MediaStore.MediaColumns.IS_PENDING) != 0) 1 else 0
                        } else old.isPending,
                    )
                }
                for (index in state.items.indices) {
                    replacements[state.items[index].id]?.let { state.items[index] = it }
                }
                targets.size
            }
        } catch (t: Throwable) {
            moves.asReversed().forEach { move ->
                try {
                    Files.move(
                        move.to.toPath(),
                        move.from.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (rollback: Throwable) {
                    t.addSuppressed(rollback)
                }
            }
            throw t
        }
    }

    internal fun mimeType(context: Context, spaceId: Int, mediaId: Long): String? {
        requireValidSpaceId(spaceId)
        requireValidMediaId(mediaId)
        return MediaMetadataStore(context, spaceId).readEntries()
            .firstOrNull { it.id == mediaId }
            ?.mimeType
    }

    internal fun isExternalMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
        val volume = uri.pathSegments.firstOrNull()?.lowercase(Locale.ROOT) ?: return false
        return volume == "external" || volume.startsWith("external_")
    }

    internal fun externalMediaType(uri: Uri): String? {
        val segments = uri.pathSegments.map { it.lowercase(Locale.ROOT) }
        return when {
            "images" in segments -> "image"
            "video" in segments -> "video"
            "audio" in segments -> "audio"
            else -> null
        }
    }

    /** Null denotes a collection URI; malformed item IDs are rejected. */
    internal fun externalMediaId(uri: Uri): Long? {
        if (!isExternalMediaStoreUri(uri)) return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        val last = segments.last()
        val id = last.toLongOrNull()
        if (id != null) {
            requireValidMediaId(id)
            return id
        }
        if (last.equals("media", ignoreCase = true)) return null
        val penultimate = segments.getOrNull(segments.size - 2)?.lowercase(Locale.ROOT)
        if (penultimate in setOf("media", "file", "downloads", "images", "video", "audio")) {
            throw IllegalArgumentException("Invalid MediaStore item id: $last")
        }
        return null
    }

    internal fun isVirtualMediaUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == AUTHORITY

    internal fun parseVirtualUri(uri: Uri): VirtualTarget {
        if (!isVirtualMediaUri(uri)) throw IllegalArgumentException("Not a virtual media URI: $uri")
        val segments = uri.pathSegments
        if (segments.size !in 1..2 || segments.any { it.isEmpty() }) {
            throw IllegalArgumentException("Invalid virtual media URI: $uri")
        }
        val spaceId = segments[0].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid virtual space id: ${segments[0]}")
        requireValidSpaceId(spaceId)
        val mediaId = if (segments.size == 2) {
            segments[1].toLongOrNull()
                ?: throw IllegalArgumentException("Invalid virtual media id: ${segments[1]}")
        } else null
        mediaId?.let(::requireValidMediaId)
        return VirtualTarget(spaceId, mediaId)
    }

    internal fun requireValidSpaceId(spaceId: Int) {
        require(spaceId in 0 until VirtualStorage.SPACE_SLOTS) {
            "Invalid virtual space id: $spaceId"
        }
    }

    internal fun requireValidMediaId(mediaId: Long) {
        require(mediaId > 0L) { "Invalid virtual media id: $mediaId" }
    }

    internal fun mimeTypeMatches(mimeType: String, filter: String): Boolean =
        if (filter.endsWith("/*")) {
            mimeType.startsWith(filter.removeSuffix("*"))
        } else {
            mimeType == filter
        }

    private fun normalizeMediaTypeFilter(mediaType: String?): String? {
        if (mediaType.isNullOrBlank()) return null
        val value = mediaType.trim().lowercase(Locale.ROOT)
        return when (value) {
            "image", "images" -> "image/*"
            "audio" -> "audio/*"
            "video" -> "video/*"
            "file", "files" -> null
            else -> {
                require(value.contains('/')) { "Invalid media type filter: $mediaType" }
                value
            }
        }
    }

    private fun normalizeMimeType(values: ContentValues, displayName: String): String {
        val supplied = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)?.trim().orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val raw = supplied.ifBlank {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
        val mimeType = raw.substringBefore(';').trim().lowercase(Locale.ROOT)
        require(mimeType.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) {
            "Invalid MIME_TYPE: $supplied"
        }
        return mimeType
    }

    private fun normalizeRelativePath(
        values: ContentValues,
        mediaType: String?,
        mimeType: String,
    ): String {
        val supplied = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
        if (supplied.isNullOrBlank()) {
            return when (mediaType) {
                "image" -> "Pictures/"
                "video" -> "Movies/"
                "audio" -> "Music/"
                else -> when {
                    mimeType.startsWith("image/") -> "Pictures/"
                    mimeType.startsWith("video/") -> "Movies/"
                    mimeType.startsWith("audio/") -> "Music/"
                    else -> "Download/"
                }
            }
        }

        val value = supplied.removeSuffix("/")
        if (value.startsWith("/") || value.contains('\\') || value.contains('\u0000')) {
            throw IllegalArgumentException("RELATIVE_PATH must be relative to the virtual sdcard")
        }
        val segments = value.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 32 } }) {
            throw IllegalArgumentException("RELATIVE_PATH contains an unsafe segment")
        }
        val canonicalRoot = when (segments.first().lowercase(Locale.ROOT)) {
            "dcim" -> "DCIM"
            "pictures" -> "Pictures"
            "movies" -> "Movies"
            "music" -> "Music"
            "download" -> "Download"
            "documents" -> "Documents"
            else -> throw IllegalArgumentException("Unsupported RELATIVE_PATH root: ${segments.first()}")
        }
        val allSegments = listOf(canonicalRoot) + segments.drop(1)
        allSegments.forEach { segment ->
            if (segment.toByteArray(Charsets.UTF_8).size > 240) {
                throw IllegalArgumentException("RELATIVE_PATH segment is too long")
            }
        }
        return allSegments.joinToString("/", postfix = "/")
    }

    private fun integerValue(values: ContentValues, key: String): Int =
        when (val value = values.get(key)) {
            null -> 0
            is Number -> value.toInt()
            else -> value.toString().toIntOrNull() ?: 0
        }

    private fun positiveLongValue(values: ContentValues, key: String, fallback: Long): Long =
        when (val value = values.get(key)) {
            is Number -> value.toLong().takeIf { it > 0L } ?: fallback
            else -> value?.toString()?.toLongOrNull()?.takeIf { it > 0L } ?: fallback
        }

    private fun openMode(mode: String): Int = when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        else -> throw IllegalArgumentException("Unsupported openFile mode: $mode")
    }

    private data class Move(val from: File, val to: File)

    internal data class VirtualTarget(val spaceId: Int, val mediaId: Long?)
}
