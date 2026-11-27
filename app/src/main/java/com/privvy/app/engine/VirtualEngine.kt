package com.privvy.app.engine

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import dalvik.system.DexClassLoader
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import java.io.File

/**
 * Layer 1 guest-execution engine (spec §5.1, Milestone 1).
 *
 * Responsibilities:
 *  - Load a guest APK (base + splits) with a [dalvik.system.DexClassLoader]
 *    whose nativeLibraryDir is the unified extracted .so directory (spec §9 risk #1).
 *  - Hook [android.app.ActivityThread.mInstrumentation] so stub Activity launches
 *    are re-targeted at the guest Activity class (this class).
 *  - Inject the guest [android.content.res.AssetManager] / [android.content.res.Resources].
 *
 * Open-source reference architecture: BlackBox-style version-gated adapters
 * (Api30Adapter / Api34Adapter / Api36Adapter) live in
 * [com.privvy.app.engine.adapters] (Milestone 4).
 */
object VirtualEngine {

    private var instrumentationHooked = false
    private val classLoaderCache = HashMap<String, DexClassLoader>()

    fun clearGuestClassLoader(packageName: String) {
        synchronized(classLoaderCache) {
            classLoaderCache.keys.removeIf { it.startsWith("$packageName|") }
        }
        GuestRuntimeFactory.clear(packageName)
    }

    /** Drops cached guest class loaders and runtimes before their files are removed. */
    fun clearAllGuestClassLoaders() {
        synchronized(classLoaderCache) {
            classLoaderCache.clear()
        }
        GuestRuntimeFactory.clearAll()
    }

    /** Idempotent: hook ActivityThread.mInstrumentation once per GUEST process. */
    fun hookInstrumentation(context: Context) {
        if (instrumentationHooked) return
        val processName = android.app.Application.getProcessName()
        if (processName == context.packageName) {
            // Host process: never wrap the real Instrumentation. Doing so breaks
            // activity lifecycle delivery and instrumentation tests.
            android.util.Log.i("PrivvyEngine", "host process — instrumentation hook skipped")
            return
        }
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val current = atClass.getMethod("currentActivityThread").invoke(null)
                ?: return
            val field = atClass.getDeclaredField("mInstrumentation")
            field.isAccessible = true
            val original = field.get(current) as Instrumentation
            field.set(current, PrivvyInstrumentation(original, context))
            com.privvy.app.hook.PackageManagerProxy.install(context)
            com.privvy.app.hook.ActivityManagerProxy.install(context)
            com.privvy.app.hook.NotificationProxy.install(context)
            instrumentationHooked = true
            android.util.Log.i("PrivvyEngine", "ActivityThread.mInstrumentation hooked")
        } catch (t: Throwable) {
            android.util.Log.e("PrivvyEngine", "failed to hook instrumentation", t)
            throw IllegalStateException("Guest isolation hooks are unavailable", t)
        }
    }

    /**
     * Build (or reuse) the guest ClassLoader for an installed APK set.
     * Native libs live in the importer's unified arm64 directory.
     */
    fun guestClassLoader(
        context: Context,
        packageName: String,
        apkPaths: List<String>,
        nativeLibDir: String?,
    ): DexClassLoader = synchronized(classLoaderCache) {
        val key = packageName + "|" + apkPaths.joinToString(":")
        classLoaderCache[key] ?: run {
            val optimized = File(context.codeCacheDir, "privvy-dex/$packageName")
            optimized.mkdirs()
            val libDir = nativeLibDir ?: File(optimized.parentFile, "$packageName/lib").absolutePath
            File(libDir).mkdirs()
            buildDexClassLoader(
                apkPaths = apkPaths,
                nativeLibDir = File(libDir),
                // The host app's PathClassLoader contains host libraries (e.g.
                // AppCompat 1.2 via biometric). Guest APKs bundle their own
                // copies of those libraries; delegating to the host would
                // shadow them and mismatch resource IDs. Parent to the system
                // loader so isolated guest classes win, with the framework
                // still resolvable.
                parentClassLoader = ClassLoader.getSystemClassLoader(),
                optimizedDir = optimized,
            ).also { classLoaderCache[key] = it }
        }
    }

    /**
     * Build a DexClassLoader for [apkPaths] (base.apk + split_config.*.apk).
     * Native libs are extracted into [nativeLibDir] (unified, per spec §9).
     */
    fun buildDexClassLoader(
        apkPaths: List<String>,
        nativeLibDir: File,
        parentClassLoader: ClassLoader,
        optimizedDir: File = nativeLibDir.parentFile ?: nativeLibDir,
    ): DexClassLoader {
        val dexPath = apkPaths.joinToString(":")
        nativeLibDir.mkdirs()
        optimizedDir.mkdirs()
        return dalvik.system.DexClassLoader(
            dexPath,
            optimizedDir.absolutePath,
            nativeLibDir.absolutePath,
            parentClassLoader,
        )
    }

    /** Extract native .so files from every APK in [apkPaths] into [outDir]. */
    fun extractNativeLibs(apkPaths: List<String>, outDir: File): File {
        outDir.mkdirs()
        for (path in apkPaths) {
            try {
                java.util.zip.ZipFile(path).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (entry.isDirectory || !name.startsWith("lib/arm64-v8a/") || !name.endsWith(".so")) {
                            continue
                        }
                        val fileName = name.substringAfterLast('/')
                        val target = File(outDir, fileName)
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        target.setReadable(true, false)
                        target.setExecutable(true, false)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("PrivvyEngine", "native lib extraction failed for $path", t)
            }
        }
        return outDir
    }
}
