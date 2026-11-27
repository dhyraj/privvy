package com.privvy.app.security

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.system.Os
import android.util.Log
import android.security.keystore.KeyProperties
import com.privvy.app.core.VirtualStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * File encryption for Privvy's built-in vault, scoped to one virtual space.
 *
 * This component only covers files explicitly imported through this API. It does
 * not transparently encrypt guest application databases, shared preferences,
 * or other files written by a running guest.
 */
object VaultCrypto {

    /** Metadata for one authenticated ciphertext file. [encryptedPath] is opaque. */
    data class VaultFile(
        val id: String,
        val spaceId: Int,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val createdAt: Long,
        val encryptedPath: String,
    )

    fun importFile(
        context: Context,
        spaceId: Int,
        source: File,
        displayName: String = source.name,
        mimeType: String? = null,
        isAuthorized: () -> Boolean = { true },
    ): VaultFile = synchronized(operationLock) {
        val appContext = context.applicationContext
        val root = spaceRoot(appContext, spaceId)
        val name = validateDisplayName(displayName)
        require(source.isFile) { "Vault source must be a regular file" }
        val sourceSize = source.length()
        require(sourceSize in 0..MAX_IN_MEMORY_FILE_SIZE) { "Vault files must be 64 MiB or smaller" }
        check(isAuthorized()) { "Vault session is no longer active" }
        val resolvedMimeType = mimeType ?: appContext.contentResolver.getType(Uri.fromFile(source))
        val safeMimeType = resolvedMimeType
            ?.takeIf { it.isNotBlank() && it.length <= 255 && it.none(Character::isISOControl) }
            ?: "application/octet-stream"
        val id = newId()
        val createdAt = System.currentTimeMillis()
        val relativePath = payloadRelativePath(id)
        val target = confinedFile(root, BLOBS_DIRECTORY, relativePath)
        require(!target.exists()) { "Vault object id already exists" }
        val metadata = VaultFile(
            id = id,
            spaceId = spaceId,
            displayName = name,
            mimeType = safeMimeType,
            size = sourceSize,
            createdAt = createdAt,
            encryptedPath = relativePath,
        )

        var published = false
        try {
            require(source.length() == metadata.size) { "Vault source changed during import" }
            FileInputStream(source).use { input ->
                writeEncrypted(appContext, spaceId, id, metadata.size, target, input)
            }
            require(source.length() == metadata.size) { "Vault source changed during import" }
            check(isAuthorized()) { "Vault session is no longer active" }
            // Flush ciphertext and its containing directory before the index can reference it.
            writeAllIndexes(appContext, root, readIndex(appContext, root) + metadata)
            published = true
            return metadata
        } finally {
            if (!published) target.safeDelete()
        }
    }

    fun list(context: Context, spaceId: Int): List<VaultFile> = synchronized(operationLock) {
        validateSpaceId(spaceId)
        readIndex(context.applicationContext, spaceRoot(context.applicationContext, spaceId))
    }

    fun openDecrypted(context: Context, file: VaultFile): ParcelFileDescriptor =
        synchronized(operationLock) {
            val appContext = context.applicationContext
            val plaintext = readBytes(appContext, file)
            val staging = File(appContext.cacheDir, DECRYPTED_DIRECTORY)
            ensurePrivateDirectory(staging)
            // UUIDs are never derived from user-visible names.
            val temporary = File.createTempFile("vault_${file.id}_", ".tmp", staging)
            try {
                writePrivateFile(temporary, plaintext)
                plaintext.fill(0)
                val descriptor = ParcelFileDescriptor.open(
                    temporary,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                try {
                    return DeletingParcelFileDescriptor(descriptor, temporary)
                } catch (failure: Throwable) {
                    descriptor.closeQuietly()
                    throw failure
                }
            } finally {
                // The wrapper deletes on close. If construction failed, delete
                // now so plaintext cannot survive that exceptional path.
                temporary.safeDelete()
            }
        }

    fun readBytes(context: Context, file: VaultFile): ByteArray = synchronized(operationLock) {
        val appContext = context.applicationContext
        val root = spaceRoot(appContext, file.spaceId)
        val entry = findEntry(appContext, root, file)
        require(entry.size <= MAX_IN_MEMORY_FILE_SIZE) { "Vault file is too large to open in memory" }
        val payload = resolvePayload(root, entry)
        require(payload.isFile && payload.canonicalFile == payload) { "Vault ciphertext is not a regular file" }
        require(payload.length() == encryptedSize(entry.size)) { "Vault ciphertext length mismatch" }
        val byteCount = Math.toIntExact(entry.size)
        val plaintext = try {
            ByteArray(byteCount)
        } catch (failure: OutOfMemoryError) {
            throw IOException("Vault file is too large to decrypt in memory", failure)
        }
        var authenticatedBytes = 0
        val finalTag = ByteArray(Long.SIZE_BYTES + TAG_BYTES)

        try {
            FileInputStream(payload).use { input ->
                val header = readExactly(input, HEADER_SIZE)
                val headerIv = header.copyOfRange(IV_OFFSET, IV_OFFSET + IV_BYTES)
                authenticateHeader(header, entry)
                val sealedHeader = readExactly(input, HEADER_SIZE + TAG_BYTES)
                decrypt(
                    appContext,
                    entry.spaceId,
                    headerIv,
                    HEADER_AAD,
                    sealedHeader,
                )
                header.fill(0)

                var offset = 0
                while (offset < byteCount) {
                    val count = minOf(BUFFER_SIZE, byteCount - offset)
                    val sealed = readExactly(input, count + IV_BYTES + TAG_BYTES)
                    val block = decrypt(
                        appContext,
                        entry.spaceId,
                        sealed.copyOfRange(0, IV_BYTES),
                        chunkAad(offset.toLong() / BUFFER_SIZE),
                        sealed.copyOfRange(IV_BYTES, sealed.size),
                    )
                    System.arraycopy(block, 0, plaintext, offset, count)
                    sealed.fill(0)
                    block.fill(0)
                    offset += count
                    authenticatedBytes = offset
                }

                // A final authenticated tag even protects an empty file against
                // truncation and verifies the sealed plaintext length.
                val finalIv = readExactly(input, IV_BYTES)
                input.readFully(finalTag)
                val finalPlaintext = decrypt(
                    appContext,
                    entry.spaceId,
                    finalIv,
                    chunkAad(byteCount.toLong() / BUFFER_SIZE),
                    finalTag,
                )
                require(
                    java.nio.ByteBuffer.wrap(finalPlaintext).long == entry.size,
                ) { "Vault metadata length mismatch" }
                finalPlaintext.fill(0)
            }
            return plaintext
        } finally {
            // Do not leave unauthenticated bytes in the caller's buffer if an
            // exception occurs before doFinal verifies a chunk or the final tag.
            if (authenticatedBytes < byteCount) plaintext.fill(0)
            finalTag.fill(0)
        }
    }

    fun exportTo(
        context: Context,
        file: VaultFile,
        destination: Uri,
        isAuthorized: () -> Boolean = { true },
    ): Boolean {
        return try {
            check(isAuthorized()) { "Vault session is no longer active" }
            val appContext = context.applicationContext
            val plaintext = readBytes(appContext, file)
            try {
                check(isAuthorized()) { "Vault session is no longer active" }
                appContext.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    output.write(plaintext)
                    output.flush()
                } ?: return false
                true
            } finally {
                plaintext.fill(0)
            }
        } catch (failure: Exception) {
            Log.e("PrivvyVault", "vault export failed", failure)
            false
        } catch (failure: OutOfMemoryError) {
            false
        }
    }

    fun delete(context: Context, file: VaultFile): Boolean = synchronized(operationLock) {
        val appContext = context.applicationContext
        val root = spaceRoot(appContext, file.spaceId)
        val entry = findEntry(appContext, root, file)
        val payload = resolvePayload(root, entry)
        val entries = readIndex(appContext, root)
        val remaining = entries.filterNot { it.id == entry.id }
        // Remove the public reference first. A stale ciphertext is harmless and
        // can be removed later, whereas a dangling index entry cannot be read.
        writeAllIndexes(appContext, root, remaining)
        payload.safeDelete()
        payload.parentFile?.let { fsyncDirectory(it) }
        !payload.exists()
    }

    fun wipeSpace(context: Context, spaceId: Int): Boolean = synchronized(operationLock) {
        validateSpaceId(spaceId)
        val appContext = context.applicationContext
        val root = File(topLevelVaultDirectory(appContext), "space_$spaceId")
        if (!VirtualStorage.deleteTreeNoFollow(root, topLevelVaultDirectory(appContext))) return false
        loadKeyStore().deleteEntry(keyAlias(spaceId))
        !root.exists()
    }

    fun wipeAll(context: Context): Boolean = synchronized(operationLock) {
        val appContext = context.applicationContext
        val root = topLevelVaultDirectory(appContext)
        if (!VirtualStorage.deleteTreeNoFollow(root, appContext.filesDir)) return false
        val keyStore = loadKeyStore()
        repeat(VirtualStorage.SPACE_SLOTS) { spaceId ->
            keyStore.deleteEntry(keyAlias(spaceId))
        }
        !root.exists()
    }

    private class DeletingParcelFileDescriptor(
        private val descriptor: ParcelFileDescriptor,
        private val temporary: File,
    ) : ParcelFileDescriptor(descriptor) {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    descriptor.close()
                } finally {
                    // Unlinking while another process still has an open fd is
                    // safe: that process can finish reading, but no new lookup
                    // can discover the pathname. Best effort also covers process
                    // death, when Java-level close callbacks cannot run.
                    temporary.safeDelete()
                }
            }
        }

        override fun getFileDescriptor(): java.io.FileDescriptor = descriptor.fileDescriptor

        override fun getStatSize(): Long = descriptor.statSize

        override fun canDetectErrors(): Boolean = descriptor.canDetectErrors()
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_PREFIX = "privvy.vault.space."
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
    private const val BUFFER_SIZE = 32 * 1024
    private val MAX_SAFE_FILE_SIZE = Math.subtractExact(Long.MAX_VALUE, encryptedOverhead().toLong())
    private const val BLOBS_DIRECTORY = "objects"
    private const val INDEX_FILE = "index.json"
    private const val DECRYPTED_DIRECTORY = "vault-decrypted"
    private const val METADATA_VERSION = 1
    private const val ID_BYTES = 16
    private const val MAX_IN_MEMORY_FILE_SIZE = 64L * 1024 * 1024
    private const val MAX_INDEX_SIZE = 16L * 1024 * 1024
    private const val MAX_INDEX_ENTRIES = 20_000

    // Header v1: magic(8), version(1), headerLength(2), idLength(1),
    // reserved(4), IV(12), spaceId(4), id(16), plaintextSize(8). The header
    // and its 16-byte GCM tag are authenticated together. Each 32 KiB chunk
    // has a 12-byte IV and 16-byte tag. A final 44-byte block seals the length.
    // Authentication also binds the chunk number as AAD, preventing reordering.
    private const val MAGIC = "PRVVAULT"
    private const val VERSION_OFFSET = 8
    private const val HEADER_LENGTH_OFFSET = 9
    private const val ID_LENGTH_OFFSET = 11
    private const val IV_OFFSET = 16
    private const val SPACE_ID_OFFSET = IV_OFFSET + IV_BYTES
    private const val ID_OFFSET = SPACE_ID_OFFSET + Integer.BYTES
    private const val SIZE_OFFSET = ID_OFFSET + ID_BYTES
    private const val HEADER_SIZE = SIZE_OFFSET + Long.SIZE_BYTES
    private val HEADER_AAD = "privvy-vault-header-v1".toByteArray(Charsets.US_ASCII)
    private const val CHUNK_AAD_PREFIX = "privvy-vault-chunk-v1"
    private val ID_PATTERN = Regex("^[0-9a-f]{32}$")
    private val RELATIVE_PATH_PATTERN = Regex("^objects/[0-9a-f]{32}\\.vlt$")
    private val operationLock = Any()
    private val random = SecureRandom()

    // A fresh IV is used for every authenticated block, including blocks from
    // the same file and the same space. This also permits parallel imports
    // without sharing a (key, IV) pair.
    private fun writeEncrypted(
        context: Context,
        spaceId: Int,
        id: String,
        size: Long,
        target: File,
        input: FileInputStream,
    ) {
        require(target.name == "$id.vlt") { "Vault ciphertext target does not match its id" }
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        val idBytes = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val headerIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(magic)
            .put(VERSION.toByte())
            .putShort(HEADER_SIZE.toShort())
            .put(ID_BYTES.toByte())
            .putInt(0)
            .put(headerIv)
            .putInt(spaceId)
            .put(idBytes)
            .putLong(size)
            .array()
        val sealedHeader = encrypt(context, spaceId, headerIv, HEADER_AAD, header)

        val output = AtomicFileOutputStream(target)
        var success = false
        try {
            output.write(header)
            output.write(sealedHeader.copyOfRange(IV_BYTES, sealedHeader.size))
            header.fill(0)
            sealedHeader.fill(0)

            var offset = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            while (offset < size) {
                val expected = minOf(BUFFER_SIZE.toLong(), size - offset).toInt()
                val count = input.read(buffer, 0, expected)
                require(count > 0) { "Vault source changed or ended during import" }
                val sealed = encrypt(
                    context,
                    spaceId,
                    randomIv(),
                    chunkAad(offset / BUFFER_SIZE),
                    buffer.copyOf(count),
                )
                output.write(sealed)
                sealed.fill(0)
                offset += count
            }
            buffer.fill(0)

            // A final authenticated tag even protects an empty file against
            // truncation and verifies the sealed plaintext length.
            val lengthBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(size).array()
            val sealedLength = encrypt(
                context,
                spaceId,
                randomIv(),
                chunkAad(size / BUFFER_SIZE),
                lengthBytes,
            )
            output.write(sealedLength)
            lengthBytes.fill(0)
            sealedLength.fill(0)
            output.fsync()
            publishFile(output, target)
            success = true
        } finally {
            if (!success) output.abort()
        }
    }

    private fun randomIv(): ByteArray = ByteArray(IV_BYTES).also(random::nextBytes)

    private fun encrypt(
        context: Context,
        spaceId: Int,
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(context, spaceId), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(CHUNK_AAD_PREFIX.toByteArray(Charsets.US_ASCII))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(
        context: Context,
        spaceId: Int,
        iv: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyFor(context, spaceId), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(CHUNK_AAD_PREFIX.toByteArray(Charsets.US_ASCII))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed)
    }

    private fun chunkAad(block: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(block).array()

    private fun topLevelVaultDirectory(context: Context): File {
        val filesRoot = context.filesDir.canonicalFile
        val directory = File(filesRoot, "vault")
        ensurePrivateDirectory(directory)
        val canonical = directory.canonicalFile
        require(canonical.parentFile == filesRoot && canonical.name == "vault") {
            "Invalid vault root"
        }
        return canonical
    }

    private fun keyFor(context: Context, spaceId: Int): SecretKey {
        validateSpaceId(spaceId)
        val alias = keyAlias(spaceId)
        val keyStore = loadKeyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return synchronized(keyLock) {
            (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey(alias).also {
                keyStore.setEntry(alias, KeyStore.SecretKeyEntry(it), null)
            }
        }
    }

    private fun generateKey(alias: String): SecretKey {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
            if (strongBox) builder.setIsStrongBoxBacked(true)
            return builder.build()
        }

        // Prefer StrongBox, then the standard AndroidKeyStore provider (TEE on
        // supported hardware). Never substitute a software key if Keystore AES
        // is unavailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                    .apply { init(spec(strongBox = true)) }
                    .generateKey()
            }.getOrNull()?.let { return it }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec(strongBox = false)) }
            .generateKey()
    }

    private fun keyAlias(spaceId: Int): String = "$KEY_PREFIX$spaceId.v2"

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun spaceRoot(context: Context, spaceId: Int): File {
        validateSpaceId(spaceId)
        val root = File(topLevelVaultDirectory(context), "space_$spaceId")
        ensurePrivateDirectory(root)
        val canonicalRoot = root.canonicalFile
        // Reject an unexpected symlink rather than following it into another
        // space or out of Privvy's private storage.
        require(canonicalRoot.parentFile == topLevelVaultDirectory(context)) {
            "Invalid vault space root"
        }
        return canonicalRoot
    }

    private fun validateSpaceId(spaceId: Int) {
        require(spaceId in 0 until VirtualStorage.SPACE_SLOTS) { "Invalid virtual space id" }
    }

    private fun validateDisplayName(name: String): String {
        require(name.isNotBlank() && name.length <= 255) { "Invalid vault display name" }
        require(name != "." && name != "..") { "Invalid vault display name" }
        require(name.none { it == '/' || it == '\\' || it.code == 0 || Character.isISOControl(it) }) {
            "Vault display name must be a file name"
        }
        return name
    }

    private fun validateRelativePath(path: String) {
        require(path.matches(RELATIVE_PATH_PATTERN)) { "Invalid vault object path" }
    }

    private fun confinedFile(root: File, directory: String, relativePath: String): File {
        require(directory == BLOBS_DIRECTORY) { "Invalid vault object directory" }
        validateRelativePath(relativePath)
        val parent = File(root, directory)
        ensurePrivateDirectory(parent)
        val canonicalRoot = root.canonicalFile
        val canonicalParent = parent.canonicalFile
        require(canonicalParent.parentFile == canonicalRoot) { "Vault object directory escaped its space" }
        val target = File(canonicalParent, relativePath.substringAfter('/'))
        require(target.parentFile == canonicalParent) { "Vault object path escaped its space" }
        require(!target.exists() || (target.isFile && target.canonicalFile == target)) {
            "Vault object path is not a regular file"
        }
        return target
    }

    private fun resolvePayload(root: File, entry: VaultFile): File {
        require(entry.id.matches(ID_PATTERN)) { "Invalid vault file id" }
        require(entry.spaceId == spaceIdFromRoot(root)) { "Vault file crossed a space boundary" }
        require(entry.encryptedPath == payloadRelativePath(entry.id)) { "Invalid vault encrypted path" }
        return confinedFile(root, BLOBS_DIRECTORY, entry.encryptedPath)
    }

    private fun payloadRelativePath(id: String): String = "$BLOBS_DIRECTORY/$id.vlt"

    private fun newId(): String = buildString(ID_BYTES * 2) {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        val hex = "0123456789abcdef"
        bytes.forEach { byte ->
            append(hex[(byte.toInt() ushr 4) and 0xf])
            append(hex[byte.toInt() and 0xf])
        }
    }

    private fun findEntry(context: Context, root: File, file: VaultFile): VaultFile {
        require(file.id.matches(ID_PATTERN)) { "Invalid vault file id" }
        require(file.spaceId == spaceIdFromRoot(root)) { "Vault file crossed a space boundary" }
        val entry = readIndex(context, root).firstOrNull { it.id == file.id }
            ?: throw IOException("Vault file is not present in this space")
        require(
            entry.id == file.id && entry.spaceId == file.spaceId &&
                entry.encryptedPath == file.encryptedPath &&
                entry.displayName == file.displayName && entry.mimeType == file.mimeType &&
                entry.size == file.size && entry.createdAt == file.createdAt
        ) { "Vault file metadata does not match its index" }
        return entry
    }

    private fun verifyIndexEnvelope(sealed: ByteArray, root: File): Boolean =
        sealed.size in (IV_BYTES + TAG_BYTES + 2)..MAX_INDEX_SIZE &&
            runCatching { unwrapIndexEnvelope(sealed, root) }.isSuccess

    private fun unwrapIndexEnvelope(sealed: ByteArray, root: File) {
        val spaceId = spaceIdFromRoot(root)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val existing = loadKeyStore().getKey(keyAlias(spaceId), null) as? SecretKey
            ?: throw IOException("Vault key is unavailable")
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(HEADER_AAD)
        cipher.updateAAD(spaceIdBytes(spaceId))
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).fill(0)
    }

    private fun readIndex(context: Context, root: File): List<VaultFile> {
        val index = File(root, INDEX_FILE)
        if (!index.exists()) return emptyList()
        require(index.isFile && index.canonicalFile == index) { "Invalid vault index file" }
        val entries = mutableListOf<VaultFile>()
        require(index.length() <= MAX_INDEX_SIZE) { "Vault index is too large" }
        val sealedIndex = index.readBytes()
        require(verifyIndexEnvelope(sealedIndex, root)) { "Invalid encrypted vault index" }
        val indexBytes = try {
            unwrapIndex(context, root, sealedIndex)
        } finally {
            sealedIndex.fill(0)
        }
        val json = try {
            JSONArray(String(indexBytes, Charsets.UTF_8))
        } finally {
            indexBytes.fill(0)
        }
        require(json.length() <= MAX_INDEX_ENTRIES) { "Too many vault index entries" }
        for (position in 0 until json.length()) {
            val item = json.getJSONObject(position)
            val id = item.getString("id")
            require(id.matches(ID_PATTERN)) { "Invalid vault index id" }
            require(entries.none { it.id == id }) { "Duplicate vault index id" }
            val path = item.getString("path")
            require(path == payloadRelativePath(id)) { "Invalid vault index path" }
            val entry = VaultFile(
                id = id,
                spaceId = item.getInt("spaceId"),
                displayName = item.getString("displayName"),
                mimeType = item.getString("mimeType"),
                size = item.getLong("size"),
                createdAt = item.getLong("createdAt"),
                encryptedPath = path,
            )
            validateEntry(entry, root)
            entries += entry
        }
        return entries.sortedByDescending { it.createdAt }
    }

    private fun validateEntry(entry: VaultFile, root: File) {
        require(entry.spaceId == spaceIdFromRoot(root)) { "Vault index crossed a space boundary" }
        require(entry.size in 0..MAX_SAFE_FILE_SIZE) { "Invalid vault metadata size" }
        require(entry.createdAt >= 0) { "Invalid vault creation time" }
        require(entry.displayName.isNotBlank() && entry.displayName.length <= 255) { "Invalid vault name" }
        validateDisplayName(entry.displayName)
        require(entry.mimeType.isNotBlank() && entry.mimeType.length <= 255) { "Invalid vault MIME type" }
        require(entry.mimeType.none { it == '\r' || it == '\n' || Character.isISOControl(it) }) {
            "Invalid vault MIME type"
        }
        resolvePayload(root, entry)
    }

    private fun spaceIdFromRoot(root: File): Int =
        root.name.removePrefix("space_").toIntOrNull() ?: throw IOException("Invalid vault root")

    private fun writeAllIndexes(
        context: Context,
        root: File,
        entries: Collection<VaultFile>,
    ) {
        validateEntries(entries, root)
        val indexBytes = encodeIndex(entries)
        try {
            // Encrypt metadata before atomically replacing the index. Readers
            // therefore never observe a partially written JSON document.
            val sealedIndex = wrapIndex(context, spaceIdFromRoot(root), indexBytes)
            AtomicFile(File(root, INDEX_FILE)).write(sealedIndex)
        } finally {
            indexBytes.fill(0)
        }
    }

    private fun encodeIndex(entries: Collection<VaultFile>): ByteArray {
        val array = JSONArray()
        entries.sortedWith(compareBy({ it.spaceId }, { it.id })).forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("spaceId", entry.spaceId)
                put("displayName", entry.displayName)
                put("mimeType", entry.mimeType)
                put("size", entry.size)
                put("createdAt", entry.createdAt)
                put("path", entry.encryptedPath)
            })
        }
        return array.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun wrapIndex(context: Context, spaceId: Int, bytes: ByteArray): ByteArray {
        val iv = randomIv()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(context, spaceId), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(HEADER_AAD)
        cipher.updateAAD(spaceIdBytes(spaceId))
        return iv + cipher.doFinal(bytes)
    }

    private fun unwrapIndex(context: Context, root: File, sealed: ByteArray): ByteArray {
        val spaceId = spaceIdFromRoot(root)
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyFor(context, spaceId), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(HEADER_AAD)
        cipher.updateAAD(spaceIdBytes(spaceId))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun spaceIdBytes(spaceId: Int): ByteArray =
        ByteBuffer.allocate(Integer.BYTES).putInt(spaceId).array()

    private fun authenticateHeader(header: ByteArray, entry: VaultFile) {
        require(header.copyOfRange(0, MAGIC.length).contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
            "Unsupported vault envelope magic"
        }
        require(header[VERSION_OFFSET] == VERSION.toByte()) { "Unsupported vault envelope version" }
        val headerLength = ByteBuffer.wrap(header, HEADER_LENGTH_OFFSET, Short.SIZE_BYTES).short.toInt() and 0xffff
        require(headerLength == HEADER_SIZE) { "Invalid vault envelope header length" }
        require(header[ID_LENGTH_OFFSET] == ID_BYTES.toByte()) { "Invalid vault envelope id length" }
        val spaceId = ByteBuffer.wrap(header, SPACE_ID_OFFSET, Integer.BYTES).int
        val hex = "0123456789abcdef"
        val id = buildString(ID_BYTES * 2) {
            for (index in ID_OFFSET until ID_OFFSET + ID_BYTES) {
                val value = header[index].toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0xf])
            }
        }
        val size = ByteBuffer.wrap(header, SIZE_OFFSET, Long.SIZE_BYTES).long
        require(spaceId == entry.spaceId && id == entry.id && size == entry.size) {
            "Vault envelope does not match its metadata"
        }
    }

    private fun readExactly(input: FileInputStream, byteCount: Int): ByteArray {
        val bytes = ByteArray(byteCount)
        input.readFully(bytes)
        return bytes
    }

    private fun FileInputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("Truncated vault envelope")
            offset += count
        }
    }

    private fun encryptedOverhead(): Int =
        HEADER_SIZE + TAG_BYTES + (IV_BYTES + Long.SIZE_BYTES + TAG_BYTES)

    private fun encryptedSize(size: Long): Long {
        val chunks = size / BUFFER_SIZE + if (size % BUFFER_SIZE == 0L) 0 else 1
        val fixed = Math.addExact(HEADER_SIZE.toLong() * 2, TAG_BYTES.toLong())
        val chunkBytes = Math.multiplyExact(chunks, (IV_BYTES + TAG_BYTES).toLong())
        val finalBytes = Math.addExact(size, (IV_BYTES + Long.SIZE_BYTES + TAG_BYTES).toLong())
        return Math.addExact(Math.addExact(fixed, chunkBytes), finalBytes)
    }

    private fun validateEntries(entries: Collection<VaultFile>, root: File) {
        require(entries.size <= MAX_INDEX_ENTRIES) { "Too many vault index entries" }
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate vault index id" }
        entries.forEach { validateEntry(it, root) }
    }

    private fun ensurePrivateDirectory(directory: File) {
        if (directory.exists()) {
            require(directory.isDirectory && directory.canonicalFile == directory) {
                "Invalid private vault directory"
            }
        } else {
            val parent = directory.parentFile
            if (parent != null && !parent.exists()) ensurePrivateDirectory(parent)
            require(directory.mkdir() || directory.isDirectory) { "Cannot create private vault directory" }
        }
        runCatching { Os.chmod(directory.path, 448) } // 0700: owner rwx only.
        require(directory.canonicalFile == directory) { "Vault directory changed during creation" }
    }

    private fun writePrivateFile(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        runCatching { Os.chmod(file.path, 384) } // 0600: owner read/write only.
        require(file.canonicalFile == file) { "Vault file changed while writing" }
    }

    private fun publishFile(output: AtomicFileOutputStream, target: File) {
        val temporary = output.finish()
        try {
            if (!temporary.renameTo(target)) throw IOException("Cannot publish vault ciphertext")
            fsyncDirectory(target.parentFile!!)
        } finally {
            temporary.safeDelete()
        }
    }

    private fun openDirectoryDescriptor(directory: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(directory, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun fsyncDirectory(directory: File) {
        openDirectoryDescriptor(directory).use { Os.fsync(it.fileDescriptor) }
    }

    private fun File.safeDelete(): Boolean {
        if (!exists()) return true
        if (canonicalFile != this) return false
        return delete()
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    private class AtomicFileOutputStream(private val target: File) : OutputStream() {
        private var temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        private val output = FileOutputStream(temporary)
        private var finished = false
        private var aborted = false

        init {
            runCatching { Os.chmod(temporary.path, 384) }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)

        override fun write(byte: Int) = output.write(byte)

        override fun flush() = output.flush()

        fun fsync() {
            output.flush()
            output.fd.sync()
        }

        fun finish(): File {
            check(!finished && !aborted)
            output.close()
            finished = true
            return temporary
        }

        fun abort() {
            if (!aborted && !finished) {
                runCatching { output.close() }
                aborted = true
            }
            temporary.safeDelete()
        }

        override fun close() {
            if (!finished && !aborted) abort()
        }
    }

    private class AtomicFile(private val target: File) {
        fun write(bytes: ByteArray) {
            var temporary: File? = null
            try {
                temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
                writePrivateFile(temporary, bytes)
                if (!temporary.renameTo(target)) throw IOException("Cannot update vault metadata")
                temporary = null
                fsyncDirectory(target.parentFile!!)
            } finally {
                temporary?.safeDelete()
            }
        }
    }

    private object ByteBuffer {
        fun allocate(size: Int) = java.nio.ByteBuffer.allocate(size)
        fun wrap(bytes: ByteArray, offset: Int, size: Int) = java.nio.ByteBuffer.wrap(bytes, offset, size)
    }

    private const val VERSION = METADATA_VERSION
    private val keyLock = Any()
}
