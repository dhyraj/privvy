package com.privvy.app.core

import android.content.Context
import android.util.Base64
import android.os.ParcelFileDescriptor
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class FileSpaceRepository(context: Context) : SpaceRepository {

    private val appContext = context.applicationContext
    private val metadataFile = File(appContext.filesDir, "keystore_meta/spaces.json")
    private val lock = Any()

    override fun listSpaces(): List<VirtualSpace> = synchronized(lock) {
        val stored = read()
        if (stored.isNotEmpty()) return stored.sortedBy { it.id }
        val initial = VirtualSpace(id = 0, name = "Personal", createdAt = System.currentTimeMillis(), pinHashHex = null)
        write(listOf(initial))
        listOf(initial)
    }

    override fun createSpace(name: String, pin: CharArray?): VirtualSpace = synchronized(lock) {
        val cleanName = name.trim()
        require(cleanName.length in 1..40) { "Space name must be 1–40 characters" }
        val cleanPin = pin?.joinToString("") ?: throw IllegalArgumentException("A PIN is required")
        require(isValidPin(cleanPin)) { "PIN must be 4–8 digits" }
        val spaces = read().toMutableList()
        if (spaces.isEmpty()) spaces += defaultSpace()
        val id = (0 until VirtualStorage.SPACE_SLOTS).firstOrNull { candidate -> spaces.none { it.id == candidate } }
            ?: throw IllegalStateException("All ${VirtualStorage.SPACE_SLOTS} space slots are in use")
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val created = VirtualSpace(
            id = id,
            name = cleanName,
            createdAt = System.currentTimeMillis(),
            pinHashHex = derive(cleanPin, salt).toHex(),
            pinSaltHex = salt.toHex(),
            isDecoy = id == 0 && spaces.size > 1,
        )
        VirtualStorage.ensureSpace(appContext, id)
        spaces += created
        write(spaces)
        created
    }

    override fun renameSpace(id: Int, name: String): VirtualSpace = synchronized(lock) {
        val cleanName = name.trim()
        require(cleanName.length in 1..40) { "Space name must be 1–40 characters" }
        val spaces = read().toMutableList()
        val index = spaces.indexOfFirst { it.id == id }
        require(index >= 0) { "Space not found" }
        val updated = spaces[index].copy(name = cleanName)
        spaces[index] = updated
        write(spaces)
        updated
    }

    override fun setPin(id: Int, pin: String) = synchronized(lock) {
        require(isValidPin(pin)) { "PIN must be 4–8 digits" }
        val spaces = read().toMutableList()
        val index = spaces.indexOfFirst { it.id == id }
        require(index >= 0) { "Space not found" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        spaces[index] = spaces[index].copy(pinHashHex = derive(pin, salt).toHex(), pinSaltHex = salt.toHex())
        write(spaces)
    }

    override fun verifyPin(id: Int, pin: String): Boolean = synchronized(lock) {
        val space = read().firstOrNull { it.id == id } ?: return false
        val hash = space.pinHashHex ?: return false
        val salt = space.pinSaltHex?.fromHex() ?: return false
        MessageDigest.isEqual(hash.fromHex(), derive(pin, salt))
    }

    override fun findSpaceByPin(pin: String): VirtualSpace? = synchronized(lock) {
        read().firstOrNull { space ->
            val hash = space.pinHashHex ?: return@firstOrNull false
            val salt = space.pinSaltHex?.fromHex() ?: return@firstOrNull false
            MessageDigest.isEqual(hash.fromHex(), derive(pin, salt))
        }
    }

    override fun deleteSpace(id: Int): Unit = synchronized(lock) {
        val spaces = read().toMutableList()
        require(spaces.size > 1) { "At least one space is required" }
        require(spaces.removeAll { it.id == id }) { "Space not found" }
        check(VirtualStorage.deleteTreeNoFollow(VirtualStorage.spaceDir(appContext, id), VirtualStorage.root(appContext))) {
            "Could not delete all space data"
        }
        write(spaces)
    }

    override fun wipeSpace(id: Int) = synchronized(lock) {
        require(read().any { it.id == id }) { "Space not found" }
        check(VirtualStorage.deleteTreeNoFollow(VirtualStorage.spaceDir(appContext, id), VirtualStorage.root(appContext))) {
            "Could not wipe all space data"
        }
        VirtualStorage.ensureSpace(appContext, id)
    }

    /** Replaces all metadata with one PIN-less Personal space after global data deletion. */
    override fun resetToDefault(): VirtualSpace = synchronized(lock) {
        val personal = defaultSpace()
        VirtualStorage.ensureSpace(appContext, personal.id)
        write(listOf(personal))
        personal
    }

    private fun read(): List<VirtualSpace> {
        if (!metadataFile.exists()) return emptyList()
        return try {
            val array = JSONArray(metadataFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optInt("id", -1)
                    if (id !in 0 until VirtualStorage.SPACE_SLOTS) continue
                    add(
                        VirtualSpace(
                            id = id,
                            name = json.optString("name", "Space $id"),
                            createdAt = json.optLong("createdAt", 0L),
                            // optString renders JSONObject.NULL as the literal "null".
                            pinHashHex = json.optString("pinHashHex").takeIf { it.isNotEmpty() && !json.isNull("pinHashHex") },
                            pinSaltHex = json.optString("pinSaltHex").takeIf { it.isNotEmpty() && !json.isNull("pinSaltHex") },
                            isDecoy = json.optBoolean("isDecoy", false),
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Cannot read space metadata", error)
        }
    }

    private fun write(spaces: List<VirtualSpace>) {
        metadataFile.parentFile?.mkdirs()
        val array = JSONArray()
        spaces.sortedBy { it.id }.forEach { space ->
            array.put(
                JSONObject().apply {
                    put("id", space.id)
                    put("name", space.name)
                    put("createdAt", space.createdAt)
                    put("pinHashHex", space.pinHashHex ?: JSONObject.NULL)
                    put("pinSaltHex", space.pinSaltHex ?: JSONObject.NULL)
                    put("isDecoy", space.isDecoy)
                },
            )
        }
        val temporary = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(array.toString(2).toByteArray())
            output.fd.sync()
        }
        check(temporary.renameTo(metadataFile)) { "Cannot publish space metadata" }
        metadataFile.parentFile?.let { directory ->
            ParcelFileDescriptor.open(directory, ParcelFileDescriptor.MODE_READ_ONLY).use {
                Os.fsync(it.fileDescriptor)
            }
        }
    }

    private fun defaultSpace() = VirtualSpace(0, "Personal", System.currentTimeMillis(), null)

    private fun isValidPin(pin: String) = pin.length in 4..8 && pin.all(Char::isDigit)

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 150_000, 256))
            .encoded

    private fun ByteArray.toHex() = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromHex(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
