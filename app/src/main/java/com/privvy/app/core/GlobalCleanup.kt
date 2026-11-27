package com.privvy.app.core

import android.content.Context
import com.privvy.app.engine.GuestReceiverRegistrar
import com.privvy.app.engine.VirtualEngine
import com.privvy.app.security.GuestDatabaseCrypto
import com.privvy.app.security.SessionLockManager
import com.privvy.app.security.VaultCrypto
import java.io.File

/** Coordinates the irreversible, symlink-safe removal of all Privvy-managed data. */
object GlobalCleanup {

    /** Stops guests, wipes owned data and keys, and returns one fresh PIN-less Personal space. */
    @Synchronized
    fun reset(context: Context): VirtualSpace {
        val appContext = context.applicationContext
        SessionLockManager.lockForReset(appContext)
        GuestReceiverRegistrar.unregisterAll(appContext)
        VirtualEngine.clearAllGuestClassLoaders()

        check(VaultCrypto.wipeAll(appContext)) { "Cannot remove vault data" }
        GuestDatabaseCrypto.wipeAll(appContext)
        deleteOwned(VirtualStorage.root(appContext), File(appContext.applicationInfo.dataDir))
        deleteOwned(VirtualStorage.importStagingDir(appContext), appContext.filesDir)
        deleteOwned(File(appContext.filesDir, "keystore_meta"), appContext.filesDir)
        deleteOwned(File(appContext.codeCacheDir, "privvy-dex"), appContext.codeCacheDir)
        deleteOwned(File(appContext.cacheDir, "vault-preview"), appContext.cacheDir)
        deleteOwned(File(appContext.cacheDir, "vault-decrypted"), appContext.cacheDir)
        appContext.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("vault-import-") }
            .forEach { deleteOwned(it, appContext.cacheDir) }

        SessionLockManager.clearSessionState(appContext)
        VirtualStorage.bootstrap(appContext)
        return FileSpaceRepository(appContext).resetToDefault()
    }

    /** Deletes only a direct child of [expectedParent] without following symlinks. */
    private fun deleteOwned(directory: File, expectedParent: File) {
        check(VirtualStorage.deleteTreeNoFollow(directory, expectedParent)) {
            "Cannot remove ${directory.name}"
        }
    }
}
