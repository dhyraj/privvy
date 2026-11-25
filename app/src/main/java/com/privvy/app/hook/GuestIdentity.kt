package com.privvy.app.hook

/**
 * Best-effort guest identity for Binder mediation.
 *
 * A rootless container cannot change the kernel UID or the Binder calling
 * identity; every guest still calls system_server as Privvy. This tracker only
 * lets client-side proxies attribute a call to the guest package that is
 * currently being instantiated or resumed in this process.
 */
object GuestIdentity {

    @Volatile
    private var processPackage: String? = null
    private val threadPackage = ThreadLocal<String?>()

    fun setProcessPackage(packageName: String?) {
        processPackage = packageName
    }

    fun currentPackage(): String? = threadPackage.get() ?: processPackage

    fun <T> withPackage(packageName: String, block: () -> T): T {
        val previousThread = threadPackage.get()
        val previousProcess = processPackage
        threadPackage.set(packageName)
        processPackage = packageName
        return try {
            block()
        } finally {
            threadPackage.set(previousThread)
            processPackage = previousProcess
        }
    }
}