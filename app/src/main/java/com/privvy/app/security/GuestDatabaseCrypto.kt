package com.privvy.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Log
import com.privvy.app.core.VirtualStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for guest SQLite databases (and their -wal/-shm/-journal
 * sidecars) under `virtual/user_<id>/data/<pkg>/databases/`.
 *
 * Rootless Privvy cannot swap the platform SQLite codec, so this is NOT
 * transparent encryption while a guest runs. Files are sealed with a
 * per-space AndroidKeyStore AES-256-GCM key when the session locks (after
 * guest processes exit) and restored when the session unlocks. While a
 * session is active the databases are plaintext inside Privvy's private
 * storage; a sudden host kill can leave them plaintext until the next lock.
 */
object GuestDatabaseCrypto {

    private const val TAG = "PrivvyDbCrypto"
    private const val SUFFIX = ".pvenc"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_PREFIX = "privvy.guestdb.space."
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val CHUNK_SIZE = 64 * 1024
    private val MAGIC = "PRVGDB01".toByteArray(Charsets.US_ASCII)
    private val AAD_PREFIX = "privvy-guestdb-v1".toByteArray(Charsets.US_ASCII)

    private val lock = Any()
    private val keyLock = Any()
    private val random = SecureRandom()

    /** Seals every plaintext database file in the space. Requires a locked session. */
    fun encryptSpace(context: Context, spaceId: Int): Int = synchronized(lock) {
        if (!SessionLockManager.isLocked(context)) return@synchronized 0
        var count = 0
        databaseFiles(context, spaceId).forEach { file ->
            if (file.name.endsWith(SUFFIX) || file.name.startsWith(".")) return@forEach
            runCatching { encryptFile(context, spaceId, file) }
                .onSuccess { count++ }
                .onFailure { Log.e(TAG, "failed to encrypt ${file.absolutePath}", it) }
        }
        if (count > 0) Log.i(TAG, "encrypted $count database files for space $spaceId")
        count
    }

    /** Restores sealed database files before guests of this space may run. */
    fun decryptSpace(context: Context, spaceId: Int): Int = synchronized(lock) {
        var count = 0
        databaseFiles(context, spaceId).forEach { file ->
            if (!file.name.endsWith(SUFFIX)) return@forEach
            runCatching { decryptFile(context, spaceId, file) }
                .onSuccess { count++ }
                .onFailure { Log.e(TAG, "failed to decrypt ${file.absolutePath}", it) }
        }
        if (count > 0) Log.i(TAG, "decrypted $count database files for space $spaceId")
        count
    }

    fun wipeSpace(context: Context, spaceId: Int) {
        runCatching { loadKeyStore().deleteEntry(keyAlias(spaceId)) }
    }

    fun wipeAll(context: Context) = synchronized(lock) {
        val keyStore = loadKeyStore()
        repeat(VirtualStorage.SPACE_SLOTS) { spaceId ->
            keyStore.deleteEntry(keyAlias(spaceId))
        }
    }

    private fun databaseFiles(context: Context, spaceId: Int): List<File> {
        // Canonicalize: applicationInfo.dataDir may be /data/user/0, a symlink
        // to /data/data, which would defeat the per-file symlink checks below.
        val dataDir = VirtualStorage.dataDir(context, spaceId).canonicalFile
        val packages = dataDir.listFiles().orEmpty()
        return packages.flatMap { pkg ->
            val databases = File(pkg, "databases")
            if (databases.isDirectory && databases.canonicalFile == databases) {
                databases.listFiles().orEmpty()
                    .filter { it.isFile && it.canonicalFile == it }
            } else emptyList()
        }
    }

    private fun relativePath(context: Context, spaceId: Int, file: File): String =
        file.canonicalPath.removePrefix(VirtualStorage.spaceDir(context, spaceId).canonicalPath + "/")

    private fun encryptFile(context: Context, spaceId: Int, file: File) {
        val target = File(file.parentFile, file.name + SUFFIX)
        val temporary = File(file.parentFile, ".${file.name}.enc.tmp")
        val relative = relativePath(context, spaceId, file)
        val key = keyFor(spaceId)
        var total = 0L
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(temporary).use { output ->
                    output.write(MAGIC)
                    val buffer = ByteArray(CHUNK_SIZE)
                    var index = 0L
                    while (true) {
                        val read = readFully(input, buffer)
                        if (read == 0) break
                        val sealed = seal(key, aad(spaceId, relative, index, final = false), buffer.copyOf(read))
                        writeBlock(output, final = false, sealed)
                        total += read
                        index++
                        if (read < buffer.size) break
                    }
                    val length = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(total).array()
                    writeBlock(output, final = true, seal(key, aad(spaceId, relative, index, final = true), length))
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot publish ${target.name}")
            if (!file.delete()) throw IOException("Cannot remove plaintext ${file.name}")
            fsyncDirectory(file.parentFile!!)
        } finally {
            temporary.delete()
        }
    }

    private fun decryptFile(context: Context, spaceId: Int, sealedFile: File) {
        val target = File(sealedFile.parentFile, sealedFile.name.removeSuffix(SUFFIX))
        val temporary = File(sealedFile.parentFile, ".${target.name}.dec.tmp")
        val relative = relativePath(context, spaceId, target)
        val key = keyFor(spaceId)
        try {
            FileInputStream(sealedFile).use { input ->
                val magic = ByteArray(MAGIC.size)
                readExactly(input, magic)
                require(magic.contentEquals(MAGIC)) { "Unsupported guest database envelope" }
                FileOutputStream(temporary).use { output ->
                    var index = 0L
                    var total = 0L
                    while (true) {
                        val flag = input.read()
                        require(flag == 0 || flag == 1) { "Invalid guest database block flag" }
                        val sizeBytes = ByteArray(Integer.BYTES)
                        readExactly(input, sizeBytes)
                        val size = ByteBuffer.wrap(sizeBytes).int
                        require(size in (IV_BYTES + TAG_BYTES)..(CHUNK_SIZE + IV_BYTES + TAG_BYTES)) {
                            "Invalid guest database block size"
                        }
                        val sealed = ByteArray(size)
                        readExactly(input, sealed)
                        val plain = open(key, aad(spaceId, relative, index, final = flag == 1), sealed)
                        if (flag == 1) {
                            require(plain.size == java.lang.Long.BYTES) { "Invalid guest database length block" }
                            require(ByteBuffer.wrap(plain).long == total) { "Guest database length mismatch" }
                            break
                        }
                        output.write(plain)
                        total += plain.size
                        index++
                    }
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot restore ${target.name}")
            if (!sealedFile.delete()) throw IOException("Cannot remove ${sealedFile.name}")
            fsyncDirectory(sealedFile.parentFile!!)
        } finally {
            temporary.delete()
        }
    }

    private fun writeBlock(output: FileOutputStream, final: Boolean, sealed: ByteArray) {
        output.write(if (final) 1 else 0)
        output.write(ByteBuffer.allocate(Integer.BYTES).putInt(sealed.size).array())
        output.write(sealed)
    }

    private fun aad(spaceId: Int, relativePath: String, index: Long, final: Boolean): ByteArray {
        val path = relativePath.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(AAD_PREFIX.size + Integer.BYTES + Integer.BYTES + path.size + java.lang.Long.BYTES + 1)
            .put(AAD_PREFIX)
            .putInt(spaceId)
            .putInt(path.size)
            .put(path)
            .putLong(index)
            .put(if (final) 1.toByte() else 0.toByte())
            .array()
    }

    private fun seal(key: SecretKey, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    private fun open(key: SecretKey, aad: ByteArray, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, sealed.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun readFully(input: FileInputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            offset += count
        }
        return offset
    }

    private fun readExactly(input: FileInputStream, buffer: ByteArray) {
        if (readFully(input, buffer) != buffer.size) throw IOException("Truncated guest database envelope")
    }

    private fun fsyncDirectory(directory: File) {
        runCatching {
            android.os.ParcelFileDescriptor.open(directory, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                .use { Os.fsync(it.fileDescriptor) }
        }
    }

    private fun keyAlias(spaceId: Int): String {
        require(spaceId in 0 until VirtualStorage.SPACE_SLOTS) { "Invalid virtual space id" }
        return "$KEY_PREFIX$spaceId"
    }

    private fun keyFor(spaceId: Int): SecretKey {
        val alias = keyAlias(spaceId)
        val keyStore = loadKeyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return synchronized(keyLock) {
            (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey(alias)
        }
    }

    private fun generateKey(alias: String): SecretKey {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
            if (strongBox) builder.setIsStrongBoxBacked(true)
            return builder.build()
        }
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

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}