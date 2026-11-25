package com.privvy.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privvy.app.core.VirtualStorage
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestDatabaseCryptoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SessionLockManager.lockSession(context)
        listOf(0, 1).forEach { space ->
            File(VirtualStorage.dataDir(context, space), PACKAGE).deleteRecursively()
        }
    }

    private fun databasesDir(spaceId: Int): File =
        File(VirtualStorage.dataDir(context, spaceId), "$PACKAGE/databases").apply { mkdirs() }

    @Test
    fun encryptsAndRestoresDatabaseFiles() {
        val dir = databasesDir(0)
        val payload = ByteArray(200 * 1024) { (it % 251).toByte() }
        val db = File(dir, "test.db").apply { writeBytes(SQLITE_HEADER + payload) }
        val wal = File(dir, "test.db-wal").apply { writeText("wal-sidecar") }
        val journal = File(dir, "test.db-journal").apply { writeBytes(ByteArray(0)) }

        assertEquals(3, GuestDatabaseCrypto.encryptSpace(context, 0))
        assertFalse(db.exists())
        assertFalse(wal.exists())
        assertFalse(journal.exists())
        val sealed = File(dir, "test.db.pvenc")
        assertTrue(sealed.isFile)
        assertFalse(sealed.readBytes().containsSubarray(payload.copyOfRange(0, 4096)))

        assertEquals(3, GuestDatabaseCrypto.decryptSpace(context, 0))
        assertFalse(sealed.exists())
        assertArrayEquals(SQLITE_HEADER + payload, db.readBytes())
        assertEquals("wal-sidecar", wal.readText())
        assertEquals(0L, journal.length())
    }

    @Test
    fun ciphertextFromAnotherSpaceIsRejected() {
        val source = databasesDir(0)
        File(source, "test.db").writeText("space-zero-secret")
        assertEquals(1, GuestDatabaseCrypto.encryptSpace(context, 0))

        val target = databasesDir(1)
        val moved = File(target, "test.db.pvenc")
        assertTrue(File(source, "test.db.pvenc").renameTo(moved))

        assertEquals(0, GuestDatabaseCrypto.decryptSpace(context, 1))
        assertFalse(File(target, "test.db").exists())
        assertTrue(moved.isFile)
    }

    @Test
    fun truncatedCiphertextIsRejected() {
        val dir = databasesDir(0)
        File(dir, "test.db").writeBytes(SQLITE_HEADER + ByteArray(1024) { 7 })
        assertEquals(1, GuestDatabaseCrypto.encryptSpace(context, 0))
        val sealed = File(dir, "test.db.pvenc")
        val bytes = sealed.readBytes()
        sealed.writeBytes(bytes.copyOf(bytes.size - 5))

        assertEquals(0, GuestDatabaseCrypto.decryptSpace(context, 0))
        assertFalse(File(dir, "test.db").exists())
    }

    @Test
    fun encryptAndDecryptAreIdempotent() {
        val dir = databasesDir(0)
        File(dir, "test.db").writeText("idempotent")
        assertEquals(1, GuestDatabaseCrypto.encryptSpace(context, 0))
        assertEquals(0, GuestDatabaseCrypto.encryptSpace(context, 0))
        assertEquals(1, GuestDatabaseCrypto.decryptSpace(context, 0))
        assertEquals(0, GuestDatabaseCrypto.decryptSpace(context, 0))
        assertEquals("idempotent", File(dir, "test.db").readText())
    }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }

    private companion object {
        const val PACKAGE = "com.privvy.dbtest"
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}