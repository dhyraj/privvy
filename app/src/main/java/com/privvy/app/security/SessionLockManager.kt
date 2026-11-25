package com.privvy.app.security

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.privvy.app.core.EngineState
import java.io.File

object SessionLockManager {

    const val ACTION_SESSION_LOCKED = "com.privvy.app.action.SESSION_LOCKED"
    const val ACTION_SESSION_STARTED = "com.privvy.app.action.SESSION_STARTED"
    private const val PREFS = "privvy_session"
    private const val KEY_ACTIVE_SPACE = "active_space"
    private const val KEY_SESSION_GENERATION = "session_generation"

    fun isLocked(context: Context): Boolean = lockFile(context).exists()

    fun isSessionActive(context: Context): Boolean {
        if (isLocked(context)) return false
        val spaceId = activeSpaceId(context)
        val generation = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION_GENERATION, 0L)
        val token = runCatching { File(guestTokensDir(context), "v$spaceId").readText() }.getOrNull()
        return generation > 0L && token == "$spaceId:$generation"
    }

    fun activeSpaceId(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACTIVE_SPACE, 0)

    fun beginSession(context: Context, spaceId: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val generation = prefs.getLong(KEY_SESSION_GENERATION, 0L) + 1L
        check(prefs.edit().putInt(KEY_ACTIVE_SPACE, spaceId).putLong(KEY_SESSION_GENERATION, generation).commit())
        lockFile(context).delete()
        GuestDatabaseCrypto.decryptSpace(context, spaceId)
        val tokens = guestTokensDir(context)
        tokens.deleteRecursively()
        check(tokens.mkdirs() || tokens.isDirectory)
        repeat(com.privvy.app.core.VirtualStorage.SPACE_SLOTS) { slot ->
            File(tokens, "v$slot").writeText("$slot:$generation")
        }
        EngineState.unlock()
        context.sendBroadcast(
            Intent(ACTION_SESSION_STARTED).setPackage(context.packageName),
        )
    }

    fun lockSession(context: Context) {
        val file = lockFile(context)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(System.currentTimeMillis().toString())
        if (!temporary.renameTo(file)) {
            file.writeText(System.currentTimeMillis().toString())
            temporary.delete()
        }
        check(guestTokensDir(context).deleteRecursively() || !guestTokensDir(context).exists())
        EngineState.lock()
        context.sendBroadcast(
            Intent(ACTION_SESSION_LOCKED).setPackage(context.packageName),
        )
        killGuestProcesses(context)
        awaitGuestExit(context)
        repeat(com.privvy.app.core.VirtualStorage.SPACE_SLOTS) { slot ->
            GuestDatabaseCrypto.encryptSpace(context, slot)
        }
    }

    // Encryption must not race a guest that still holds plaintext database
    // descriptors; killProcess is asynchronous.
    private fun awaitGuestExit(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val guestRunning = manager.runningAppProcesses.orEmpty().any {
                it.processName.startsWith("${context.packageName}:v")
            }
            if (!guestRunning) return
            SystemClock.sleep(100L)
        }
    }

    fun ensureGuestAllowed(context: Context): Boolean {
        val spaceId = EngineState.spaceId()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val generation = prefs.getLong(KEY_SESSION_GENERATION, 0L)
        val token = runCatching { File(guestTokensDir(context), "v$spaceId").readText() }.getOrNull()
        val hostRunning = context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.any { it.processName == context.packageName } == true
        val allowed = hostRunning &&
            !isLocked(context) &&
            activeSpaceId(context) == spaceId &&
            generation > 0L &&
            token == "$spaceId:$generation"
        if (allowed) EngineState.unlock() else EngineState.lock()
        return allowed
    }

    fun killGuestProcesses(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val ownProcesses = manager.runningAppProcesses.orEmpty().filter { process ->
            process.pkgList.any { it == context.packageName } &&
                process.processName.startsWith("${context.packageName}:")
        }
        ownProcesses.forEach { process ->
            if (process.pid != Process.myPid()) {
                runCatching { Process.killProcess(process.pid) }
            }
        }
    }

    private fun lockFile(context: Context) = File(context.filesDir, "session.lock")

    private fun guestTokensDir(context: Context) = File(context.filesDir, "guest-session")
}
