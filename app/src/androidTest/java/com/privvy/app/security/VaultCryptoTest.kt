package com.privvy.app.security

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultCryptoTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "vault-crypto-test-${System.nanoTime()}")
        check(root.mkdirs())
        context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

            override fun getApplicationContext(): Context = this
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun roundTripsEmptyAndChunkBoundaryFiles() {
        for (size in listOf(0, 1, 32_767, 32_768, 32_769, 1_048_577)) {
            val plaintext = ByteArray(size) { (it * 31 + size).toByte() }
            val source = source("boundary-$size.bin", plaintext)
            val file = VaultCrypto.importFile(context, 0, source, source.name, "application/octet-stream")

            assertArrayEquals(plaintext, VaultCrypto.readBytes(context, file))
            assertTrue(VaultCrypto.list(context, 0).any { it.id == file.id })

            val payload = File(context.filesDir, "vault/space_0/${file.encryptedPath}")
            val ciphertext = payload.readBytes()
            if (plaintext.size >= 32) {
                assertFalse(ciphertext.containsSequence(plaintext.copyOfRange(0, 32)))
            }
            val index = File(context.filesDir, "vault/space_0/index.json").readText()
            assertFalse(index.contains(file.displayName))

            assertTrue(VaultCrypto.delete(context, file))
            assertFalse(VaultCrypto.list(context, 0).any { it.id == file.id })
        }
    }

    @Test
    fun rejectsModifiedCiphertextAndIndex() {
        val plaintext = ByteArray(4096) { (it * 17).toByte() }
        val source = source("tamper.bin", plaintext)
        val file = VaultCrypto.importFile(context, 0, source, source.name, "application/octet-stream")
        val payload = File(context.filesDir, "vault/space_0/${file.encryptedPath}")
        val modifiedPayload = payload.readBytes()
        modifiedPayload[140] = (modifiedPayload[140].toInt() xor 1).toByte()
        payload.writeBytes(modifiedPayload)

        assertThrows(Exception::class.java) {
            VaultCrypto.readBytes(context, file)
        }
        assertTrue(VaultCrypto.delete(context, file))

        val secondSource = source("index-tamper.bin", plaintext)
        VaultCrypto.importFile(context, 0, secondSource, secondSource.name, "application/octet-stream")
        val index = File(context.filesDir, "vault/space_0/index.json")
        val modifiedIndex = index.readBytes()
        modifiedIndex[modifiedIndex.lastIndex] = (modifiedIndex.last().toInt() xor 1).toByte()
        index.writeBytes(modifiedIndex)

        assertThrows(Exception::class.java) {
            VaultCrypto.list(context, 0)
        }
    }

    @Test
    fun keepsSpaceIndexesSeparate() {
        val firstSource = source("personal.txt", "personal".toByteArray())
        val secondSource = source("work.txt", "work".toByteArray())
        val personal = VaultCrypto.importFile(context, 0, firstSource, firstSource.name, "text/plain")
        val work = VaultCrypto.importFile(context, 1, secondSource, secondSource.name, "text/plain")

        assertEquals(listOf(personal.id), VaultCrypto.list(context, 0).map { it.id })
        assertEquals(listOf(work.id), VaultCrypto.list(context, 1).map { it.id })
        assertArrayEquals("personal".toByteArray(), VaultCrypto.readBytes(context, personal))
        assertArrayEquals("work".toByteArray(), VaultCrypto.readBytes(context, work))
    }

    @Test
    fun wipeDeletesVaultDataAndOnlyTheSelectedSpaceKey() {
        val personalSource = source("wipe-personal.txt", "personal".toByteArray())
        val workSource = source("wipe-work.txt", "work".toByteArray())
        VaultCrypto.importFile(context, 0, personalSource, personalSource.name, "text/plain")
        VaultCrypto.importFile(context, 1, workSource, workSource.name, "text/plain")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("Personal key was not created", keyStore.containsAlias("privvy.vault.space.0.v2"))
        assertTrue("Work key was not created", keyStore.containsAlias("privvy.vault.space.1.v2"))

        assertTrue(VaultCrypto.wipeSpace(context, 0))

        assertTrue(VaultCrypto.list(context, 0).isEmpty())
        assertFalse("Personal key survived wipe", keyStore.containsAlias("privvy.vault.space.0.v2"))
        assertTrue("Work key was removed", keyStore.containsAlias("privvy.vault.space.1.v2"))
        assertArrayEquals("work".toByteArray(), VaultCrypto.readBytes(context, VaultCrypto.list(context, 1).single()))
        assertTrue(VaultCrypto.wipeSpace(context, 1))
    }

    @Test
    fun enforcesSessionAuthorization() {
        val source = source("unauthorized.txt", "secret".toByteArray())

        assertThrows(IllegalStateException::class.java) {
            VaultCrypto.importFile(
                context,
                0,
                source,
                source.name,
                "text/plain",
                isAuthorized = { false },
            )
        }
        assertTrue(VaultCrypto.list(context, 0).isEmpty())
    }

    @Test
    fun exportsExactPlaintextThroughMediaStore() {
        val plaintext = ByteArray(70_000) { (it * 13).toByte() }
        val source = source("export.bin", plaintext)
        val file = VaultCrypto.importFile(context, 0, source, source.name, "application/octet-stream")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "privvy-vault-test-${System.nanoTime()}.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PrivvyTests")
        }
        val resolver = context.contentResolver
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create test download")
        try {
            assertFalse(VaultCrypto.exportTo(context, file, destination, isAuthorized = { false }))
            assertTrue(VaultCrypto.exportTo(context, file, destination))
            val exported = resolver.openInputStream(destination)!!.use { it.readBytes() }
            assertArrayEquals(plaintext, exported)
        } finally {
            resolver.delete(destination, null, null)
            VaultCrypto.delete(context, file)
        }
    }

    private fun source(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply { writeBytes(bytes) }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty()) return false
        outer@ for (start in 0..size - sequence.size) {
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
