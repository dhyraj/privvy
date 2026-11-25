package com.privvy.app.media

import android.content.Context
import com.privvy.app.core.VirtualStorage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class MediaEntry(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val isPending: Int,
    val ownerPackageName: String?,
)

internal class MetadataState(
    var nextId: Long,
    val items: MutableList<MediaEntry>,
)

/**
 * A small, process-safe JSON database. The lock is deliberately outside sdcard/
 * so a guest cannot mistake bookkeeping for media. JSON replacement is an
 * atomic rename in the same directory, and the file is fsynced before rename.
 */
internal class MediaMetadataStore(
    context: Context,
    private val spaceId: Int,
) {
    private val appContext = context.applicationContext ?: context
    private val spaceDir = VirtualStorage.spaceDir(appContext, spaceId)
    private val sdcardDir = VirtualStorage.sdcardDir(appContext, spaceId)
    private val metadataDir = File(spaceDir, "media")
    private val metadataFile = File(metadataDir, "index.json")
    private val lockFile = File(metadataDir, "index.lock")

    init {
        VirtualMediaStore.requireValidSpaceId(spaceId)
    }

    fun readEntries(): List<MediaEntry> = locked(shared = true) { state ->
        state.items.toList()
    }

    fun <T> mutate(block: (MetadataState) -> T): T = locked(shared = false) { state ->
        val result = block(state)
        write(state)
        result
    }

    fun resolveFile(entry: MediaEntry, createParents: Boolean = false): File =
        resolveFile(sdcardDir, entry.relativePath, entry.displayName, createParents)

    private fun <T> locked(shared: Boolean, block: (MetadataState) -> T): T {
        if (!spaceDir.mkdirs() && !spaceDir.isDirectory) {
            throw IOException("Cannot create virtual space directory: $spaceDir")
        }
        if (!sdcardDir.mkdirs() && !sdcardDir.isDirectory) {
            throw IOException("Cannot create virtual sdcard directory: $sdcardDir")
        }
        if (!metadataDir.mkdirs() && !metadataDir.isDirectory) {
            throw IOException("Cannot create media metadata directory: $metadataDir")
        }

        // FileLock is JVM-wide. Serialize locally before taking the OS lock to
        // avoid OverlappingFileLockException between threads in one process.
        val localLock = localLocks.computeIfAbsent(lockFile.absolutePath) { Any() }
        return synchronized(localLock) {
            RandomAccessFile(lockFile, "rw").use { raf ->
                val channel = raf.channel
                val lock = if (shared) {
                    channel.lock(0, Long.MAX_VALUE, true)
                } else {
                    channel.lock(0, Long.MAX_VALUE, false)
                }
                lock.use { block(read()) }
            }
        }
    }

    private fun read(): MetadataState {
        if (!metadataFile.exists()) return MetadataState(1L, mutableListOf())
        if (!metadataFile.isFile || metadataFile.length() == 0L) {
            throw IOException("Invalid media metadata file: $metadataFile")
        }

        val json = try {
            JSONObject(metadataFile.readText(Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException("Corrupt media metadata: $metadataFile", e)
        }
        val version = json.optInt("version", 0)
        if (version != METADATA_VERSION) {
            throw IOException("Unsupported media metadata version: $version")
        }

        val array = json.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<MediaEntry>(array.length())
        val ids = HashSet<Long>()
        var maximumId = 0L
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
                ?: throw IOException("Invalid media metadata row at index $index")
            val entry = MediaEntry(
                id = obj.optLong("id", 0L),
                displayName = sanitizeDisplayName(obj.optString("display_name", "")),
                mimeType = obj.optString("mime_type", ""),
                relativePath = normalizeStoredRelativePath(obj.optString("relative_path", "")),
                size = obj.optLong("size", 0L).coerceAtLeast(0L),
                dateAdded = obj.optLong("date_added", 0L).coerceAtLeast(0L),
                dateModified = obj.optLong("date_modified", 0L).coerceAtLeast(0L),
                width = obj.optInt("width", 0).coerceAtLeast(0),
                height = obj.optInt("height", 0).coerceAtLeast(0),
                isPending = if (obj.optInt("is_pending", 0) != 0) 1 else 0,
                ownerPackageName = obj.optString("owner_package_name", "")
                    .takeIf { it.isNotEmpty() && !obj.isNull("owner_package_name") },
            )
            if (entry.id <= 0L || !ids.add(entry.id)) {
                throw IOException("Invalid or duplicate media id ${entry.id}")
            }
            if (entry.mimeType.isBlank()) {
                throw IOException("Missing MIME type for media id ${entry.id}")
            }
            items.add(entry)
            maximumId = maxOf(maximumId, entry.id)
        }

        val storedNext = json.optLong("next_id", 1L)
        val nextId = maxOf(storedNext, maximumId + 1).coerceAtLeast(1L)
        if (nextId == Long.MAX_VALUE) throw IOException("Media id space exhausted")
        return MetadataState(nextId, items)
    }

    private fun write(state: MetadataState) {
        val json = JSONObject()
            .put("version", METADATA_VERSION)
            .put("next_id", state.nextId)
        val rows = JSONArray()
        state.items.sortedBy { it.id }.forEach { entry ->
            rows.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("display_name", entry.displayName)
                    .put("mime_type", entry.mimeType)
                    .put("relative_path", entry.relativePath)
                    .put("size", entry.size)
                    .put("date_added", entry.dateAdded)
                    .put("date_modified", entry.dateModified)
                    .put("width", entry.width)
                    .put("height", entry.height)
                    .put("is_pending", entry.isPending)
                    .put("owner_package_name", entry.ownerPackageName ?: JSONObject.NULL),
            )
        }
        json.put("items", rows)

        val temporary = File(metadataDir, "index.json.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                metadataFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private const val METADATA_VERSION = 1
        private val localLocks = ConcurrentHashMap<String, Any>()

        fun sanitizeDisplayName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty() || name == "." || name == "..") {
                throw IllegalArgumentException("DISPLAY_NAME must be a file name")
            }
            if (name.contains('/') || name.contains('\\') || name.contains('\u0000') ||
                name.any { it.code < 32 }
            ) {
                throw IllegalArgumentException("DISPLAY_NAME contains an invalid path character")
            }
            if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
                throw IllegalArgumentException("DISPLAY_NAME is too long")
            }
            return name
        }

        /** Normalizes an already confined relative path from trusted metadata. */
        fun normalizeStoredRelativePath(raw: String): String {
            val value = raw.removeSuffix("/")
            if (value.isEmpty() || value.startsWith("/") || value.contains('\\') ||
                value.contains('\u0000')
            ) {
                throw IOException("Invalid relative path in media metadata")
            }
            val segments = value.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 32 } }) {
                throw IOException("Unsafe relative path in media metadata")
            }
            return segments.joinToString("/", postfix = "/")
        }

        fun resolveFile(
            root: File,
            relativePath: String,
            displayName: String,
            createParents: Boolean,
        ): File {
            val canonicalRoot = root.canonicalFile
            val normalized = normalizeStoredRelativePath(relativePath)
            val name = sanitizeDisplayName(displayName)
            val parent = File(canonicalRoot, normalized)
            if (createParents && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Cannot create media directory: $parent")
            }
            if (!parent.isDirectory) throw FileNotFoundException("Missing media directory: $parent")

            val canonicalParent = parent.canonicalFile
            if (!canonicalParent.toPath().startsWith(canonicalRoot.toPath())) {
                throw IOException("Media path escapes the virtual sdcard")
            }
            val file = File(canonicalParent, name)
            if (Files.isSymbolicLink(file.toPath())) {
                throw IOException("Refusing symbolic-link media file: $file")
            }
            if (!file.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
                throw IOException("Media path escapes the virtual sdcard")
            }
            return file
        }

        fun createUniqueFile(parent: File, desiredName: String): File {
            val desired = sanitizeDisplayName(desiredName)
            val first = File(parent, desired)
            if (!Files.exists(first.toPath()) && !Files.isSymbolicLink(first.toPath()) &&
                first.createNewFile()
            ) {
                return first
            }

            val dot = desired.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val stem = if (dot >= 0) desired.substring(0, dot) else desired
            val extension = if (dot >= 0) desired.substring(dot) else ""
            repeat(64) {
                val suffix = "_" + UUID.randomUUID().toString().replace("-", "").take(12) + extension
                val maxStemBytes = (MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
                    .coerceAtLeast(1)
                val shortenedStem = truncateUtf8(stem, maxStemBytes)
                val candidate = File(parent, shortenedStem + suffix)
                if (!Files.exists(candidate.toPath()) && !Files.isSymbolicLink(candidate.toPath()) &&
                    candidate.createNewFile()
                ) {
                    return candidate
                }
            }
            throw IOException("Unable to allocate a unique media file name")
        }

        fun collisionName(desiredName: String): String {
            val desired = sanitizeDisplayName(desiredName)
            val dot = desired.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val stem = if (dot >= 0) desired.substring(0, dot) else desired
            val extension = if (dot >= 0) desired.substring(dot) else ""
            val suffix = "_" + UUID.randomUUID().toString().replace("-", "").take(12) + extension
            val maxStemBytes = (MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
                .coerceAtLeast(1)
            return truncateUtf8(stem, maxStemBytes) + suffix
        }

        private fun truncateUtf8(value: String, maxBytes: Int): String {
            if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
            var end = value.length
            while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
                end--
            }
            return value.substring(0, end).ifEmpty { "media" }
        }

        private const val MAX_NAME_BYTES = 240
    }
}
