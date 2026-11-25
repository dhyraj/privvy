package com.privvy.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.core.VirtualStorage
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object GuestApkImporter {

    fun importFromUri(context: Context, uri: Uri, spaceId: Int): InstalledApp {
        val staging = File(VirtualStorage.importStagingDir(context), "import-${System.currentTimeMillis()}.tmp")
        staging.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            staging.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open $uri")
        return try {
            importFromFile(context, staging, spaceId)
        } finally {
            staging.delete()
        }
    }

    fun importInstalled(context: Context, packageName: String, spaceId: Int): InstalledApp {
        require(packageName != context.packageName) { "Privvy cannot clone itself" }
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val paths = buildList {
            info.sourceDir?.let(::add)
            info.splitSourceDirs?.forEach(::add)
        }
        require(paths.isNotEmpty()) { "Installed APK not found" }
        val bundle = File(VirtualStorage.importStagingDir(context), "installed-$packageName-${System.currentTimeMillis()}.apks")
        bundle.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(bundle.outputStream()).use { output ->
            paths.forEachIndexed { index, path ->
                val name = if (index == 0) "base.apk" else "split_$index.apk"
                output.putNextEntry(java.util.zip.ZipEntry(name))
                File(path).inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return try {
            importFromFile(context, bundle, spaceId, info.nativeLibraryDir)
        } finally {
            bundle.delete()
        }
    }

    fun importFromFile(
        context: Context,
        source: File,
        spaceId: Int,
        nativeLibrarySourceDir: String? = null,
    ): InstalledApp {
        if (!source.exists()) throw IllegalArgumentException("APK not found: ${source.absolutePath}")

        val bundleDir = File(VirtualStorage.importStagingDir(context), "bundle-${System.currentTimeMillis()}")
        bundleDir.mkdirs()
        val apkFiles = collectApks(source, bundleDir)
        if (apkFiles.isEmpty()) throw IllegalArgumentException("No APK found in ${source.name}")

        val baseFile = selectBase(apkFiles)
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val pkgInfo = pm.getPackageArchiveInfo(
            baseFile.absolutePath,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw IllegalStateException("Cannot parse ${baseFile.name}")

        val appInfo = pkgInfo.applicationInfo
            ?: throw IllegalStateException("No application info in ${baseFile.name}")
        appInfo.sourceDir = baseFile.absolutePath
        appInfo.publicSourceDir = baseFile.absolutePath

        val packageName = appInfo.packageName
            ?: pkgInfo.packageName
            ?: throw IllegalStateException("Missing package name")

        val label = try {
            appInfo.loadLabel(pm).toString()
        } catch (_: Throwable) {
            packageName
        }

        val versionName = pkgInfo.versionName
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        val launchInfo = ApkManifestParser.parse(baseFile, packageName)
        val launchActivity = launchInfo?.activityName
            ?: ApkManifestParser.fallbackLaunchActivity(pkgInfo.activities)
            ?: throw IllegalStateException("No launcher activity found in $packageName")
        val launchActivityInfo = pkgInfo.activities?.firstOrNull { it.name == launchActivity }
        val launchTheme = launchInfo?.themeResId?.takeIf { it != 0 }
            ?: launchActivityInfo?.theme?.takeIf { it != 0 }
            ?: appInfo.theme

        // Copy APKs into Privvy's private shared store.
        val appDir = VirtualStorage.appApkDir(context, packageName)
        appDir.mkdirs()
        // Clear previous import of the same package.
        appDir.listFiles()?.forEach { it.deleteRecursively() }
        appDir.mkdirs()

        val finalApkPaths = ArrayList<String>()
        apkFiles.forEachIndexed { index, file ->
            val name = if (file.name.equals("base.apk", ignoreCase = true)) {
                "base.apk"
            } else {
                "split_${index}.apk"
            }
            val target = File(appDir, name)
            file.copyTo(target, overwrite = true)
            finalApkPaths.add(target.absolutePath)
        }

        // Extract native libs into a unified arm64 directory.
        val nativeLibDir = File(appDir, "lib/arm64")
        VirtualEngine.extractNativeLibs(finalApkPaths, nativeLibDir)
        nativeLibrarySourceDir?.let { sourceDir ->
            File(sourceDir).takeIf { it.isDirectory }?.listFiles { file ->
                file.isFile && file.name.endsWith(".so")
            }?.forEach { file ->
                val target = File(nativeLibDir, file.name)
                file.copyTo(target, overwrite = true)
                target.setReadable(true, false)
                target.setExecutable(true, false)
            }
        }

        // Save icon if possible.
        val iconPath = saveIcon(context, appInfo, pm, appDir)

        val installed = InstalledApp(
            packageName = packageName,
            label = label,
            versionName = versionName,
            versionCode = versionCode,
            launchActivity = launchActivity,
            launchTheme = launchTheme,
            apkPaths = finalApkPaths,
            nativeLibDir = nativeLibDir.absolutePath,
            iconPath = iconPath,
            installedAt = System.currentTimeMillis(),
            applicationClassName = appInfo.className,
            requestedPermissions = pkgInfo.requestedPermissions.orEmpty().toList(),
            activityNames = pkgInfo.activities.orEmpty().map { it.name },
            serviceNames = pkgInfo.services.orEmpty().map { it.name },
            receiverNames = pkgInfo.receivers.orEmpty().map { it.name },
            providerNames = pkgInfo.providers.orEmpty().map { it.name },
            providerAuthorities = pkgInfo.providers.orEmpty().flatMap { it.authority?.split(';').orEmpty() },
            signatureBase64 = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pkgInfo.signingInfo?.apkContentsSigners.orEmpty().map {
                    android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)
                }
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures.orEmpty().map {
                    android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)
                }
            },
            targetSdk = appInfo.targetSdkVersion,
        )

        VirtualEngine.clearGuestClassLoader(packageName)
        SpaceAppRegistry.upsert(context, spaceId, installed)
        Log.i("PrivvyImporter", "imported $packageName into space $spaceId (${finalApkPaths.size} apks)")
        return installed
    }

    private fun collectApks(source: File, bundleDir: File): List<File> {
        // Single APK file.
        if (source.name.endsWith(".apk", ignoreCase = true)) {
            return listOf(source)
        }
        // Bundle (.apks / .xapk / .zip): extract every *.apk entry.
        val apks = mutableListOf<File>()
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) continue
                val target = File(bundleDir, File(entry.name).name)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                apks.add(target)
            }
        }
        return apks
    }

    private fun selectBase(apkFiles: List<File>): File {
        apkFiles.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }?.let { return it }
        // Otherwise pick the largest APK (base is usually the biggest).
        return apkFiles.maxByOrNull { it.length() } ?: apkFiles.first()
    }

    private fun saveIcon(context: Context, appInfo: android.content.pm.ApplicationInfo, pm: PackageManager, appDir: File): String? {
        return try {
            val drawable = appInfo.loadIcon(pm)
            val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val target = File(appDir, "icon.png")
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            target.absolutePath
        } catch (t: Throwable) {
            Log.w("PrivvyImporter", "icon extraction failed", t)
            null
        }
    }
}
