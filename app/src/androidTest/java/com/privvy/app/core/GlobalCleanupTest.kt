package com.privvy.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privvy.app.engine.GuestPermissionStore
import com.privvy.app.security.GuestDatabaseCrypto
import com.privvy.app.security.SessionLockManager
import com.privvy.app.security.VaultCrypto
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalCleanupTest {

    private lateinit var sandbox: File
    private lateinit var dataRoot: File
    private lateinit var filesRoot: File
    private lateinit var cacheRoot: File
    private lateinit var codeCacheRoot: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val target = ApplicationProvider.getApplicationContext<Context>()
        sandbox = File(target.cacheDir, "global-cleanup-test-${System.nanoTime()}")
        dataRoot = File(sandbox, "data").apply { mkdirs() }
        filesRoot = File(sandbox, "files").apply { mkdirs() }
        cacheRoot = File(sandbox, "cache").apply { mkdirs() }
        codeCacheRoot = File(sandbox, "code-cache").apply { mkdirs() }
        val applicationInfo = ApplicationInfo(target.applicationInfo).apply {
            dataDir = dataRoot.absolutePath
        }
        context = object : ContextWrapper(target) {
            override fun getApplicationInfo(): ApplicationInfo = applicationInfo
            override fun getFilesDir(): File = filesRoot
            override fun getCacheDir(): File = cacheRoot
            override fun getCodeCacheDir(): File = codeCacheRoot
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                target.getSharedPreferences("global-cleanup-test-$name", mode)
        }
        VirtualStorage.bootstrap(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("privvy_session", Context.MODE_PRIVATE).edit().clear().commit()
        EngineState.lock()
        sandbox.deleteRecursively()
    }

    @Test
    fun resetRemovesPrivvyDataAndKeysButPreservesUnrelatedFiles() {
        val repository = FileSpaceRepository(context)
        repository.listSpaces()
        repository.setPin(0, "1234")
        repository.createSpace("Work", "5678".toCharArray())
        repeat(2) { spaceId ->
            val packageName = "com.privvy.cleanup.guest$spaceId"
            val apk = File(VirtualStorage.appApkDir(context, packageName), "base.apk").apply {
                parentFile?.mkdirs()
                writeText("guest-apk-$spaceId")
            }
            SpaceAppRegistry.upsert(
                context,
                spaceId,
                InstalledApp(
                    packageName = packageName,
                    label = "Guest $spaceId",
                    versionName = "1.0",
                    versionCode = 1L,
                    launchActivity = "MainActivity",
                    launchTheme = 0,
                    apkPaths = listOf(apk.absolutePath),
                    nativeLibDir = null,
                    iconPath = null,
                    installedAt = 1L,
                ),
            )
            GuestPermissionStore.setGranted(context, spaceId, packageName, "android.permission.CAMERA", true)
        }

        val database = File(VirtualStorage.dataDir(context, 0), "com.privvy.cleanup.guest0/databases/test.db").apply {
            parentFile?.mkdirs()
            writeText("database-secret")
        }
        SessionLockManager.lockSession(context)
        assertTrue(File(database.parentFile, "test.db.pvenc").isFile)

        val vaultSource = File(sandbox, "vault-source.txt").apply { writeText("vault-secret") }
        VaultCrypto.importFile(context, 0, vaultSource, "personal.txt", "text/plain")
        VaultCrypto.importFile(context, 1, vaultSource, "work.txt", "text/plain")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias("privvy.vault.space.0.v2"))
        assertTrue(keyStore.containsAlias("privvy.guestdb.space.0"))

        context.getSharedPreferences("privvy_session", Context.MODE_PRIVATE).edit()
            .putInt("active_space", 1)
            .putLong("session_generation", 12L)
            .commit()
        File(filesRoot, "guest-session").apply { mkdirs() }
        File(filesRoot, "guest-session/v1").writeText("1:12")
        File(VirtualStorage.importStagingDir(context), "guest-test-app.apk").apply {
            parentFile?.mkdirs()
            writeText("staged-apk")
        }
        File(cacheRoot, "vault-preview").apply { mkdirs() }
        File(cacheRoot, "vault-preview/plain.txt").writeText("preview-secret")
        File(cacheRoot, "vault-decrypted").apply { mkdirs() }
        File(cacheRoot, "vault-import-leftover.tmp").writeText("import-secret")
        File(codeCacheRoot, "privvy-dex/com.privvy.cleanup.guest0").apply { mkdirs() }
        File(codeCacheRoot, "privvy-dex/com.privvy.cleanup.guest0/classes.dex").writeText("dex")

        val outside = File(sandbox, "outside").apply { mkdirs() }
        val outsideFile = File(outside, "keep.txt").apply { writeText("outside") }
        Files.createSymbolicLink(
            File(VirtualStorage.dataDir(context, 0), "escape").toPath(),
            outside.toPath(),
        )
        val hostSentinel = File(dataRoot, "host-app-sentinel.txt").apply { writeText("host") }
        val unrelatedFile = File(filesRoot, "host-note.txt").apply { writeText("host") }
        val unrelatedCache = File(cacheRoot, "unrelated.tmp").apply { writeText("host") }
        val unrelatedDex = File(codeCacheRoot, "unrelated.dex").apply { writeText("host") }

        val personal = GlobalCleanup.reset(context)

        assertEquals(0, personal.id)
        assertEquals("Personal", personal.name)
        assertNull(personal.pinHashHex)
        assertEquals(listOf(personal), repository.listSpaces())
        assertTrue(SpaceAppRegistry.list(context, 0).isEmpty())
        assertTrue(SpaceAppRegistry.list(context, 1).isEmpty())
        assertFalse(File(filesRoot, "vault").exists())
        assertFalse(VirtualStorage.importStagingDir(context).exists())
        assertFalse(File(cacheRoot, "vault-preview").exists())
        assertFalse(File(cacheRoot, "vault-decrypted").exists())
        assertFalse(File(cacheRoot, "vault-import-leftover.tmp").exists())
        assertFalse(File(codeCacheRoot, "privvy-dex").exists())
        assertFalse(File(filesRoot, "guest-session").exists())
        assertTrue(File(filesRoot, "session.lock").isFile)
        assertEquals(0, SessionLockManager.activeSpaceId(context))
        assertEquals(0L, context.getSharedPreferences("privvy_session", Context.MODE_PRIVATE).getLong("session_generation", 0L))
        assertFalse(SessionLockManager.isSessionActive(context))
        assertTrue(EngineState.isLocked)
        assertTrue(VirtualStorage.apksDir(context).listFiles().orEmpty().isEmpty())
        assertTrue(VirtualStorage.dataDir(context, 0).isDirectory)
        assertTrue(VirtualStorage.sdcardDir(context, 0).isDirectory)
        assertFalse(keyStore.containsAlias("privvy.vault.space.0.v2"))
        assertFalse(keyStore.containsAlias("privvy.vault.space.1.v2"))
        assertFalse(keyStore.containsAlias("privvy.guestdb.space.0"))
        assertFalse(keyStore.containsAlias("privvy.guestdb.space.1"))
        assertTrue(hostSentinel.isFile)
        assertTrue(unrelatedFile.isFile)
        assertTrue(unrelatedCache.isFile)
        assertTrue(unrelatedDex.isFile)
        assertTrue(outsideFile.isFile)

        GlobalCleanup.reset(context)
        assertEquals(listOf(0), repository.listSpaces().map { it.id })
        assertTrue(hostSentinel.isFile)
    }
}
