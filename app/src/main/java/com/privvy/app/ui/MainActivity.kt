package com.privvy.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.privvy.app.BuildConfig
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.core.VirtualStorage
import com.privvy.app.engine.GuestApkImporter
import com.privvy.app.engine.VirtualAppLauncher
import com.privvy.app.security.AuthManager
import com.privvy.app.security.SessionGuardService
import com.privvy.app.ui.auth.LockScreen
import com.privvy.app.ui.home.HomeScreen
import com.privvy.app.ui.theme.PrivvyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(true)
    private var notificationPermissionAsked = false

    private val apps = mutableStateListOf<InstalledApp>()
    private var importing by mutableStateOf(false)
    private var importMessage by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startSessionService()
    }

    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()

        apps.addAll(SpaceAppRegistry.list(this, 0))

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }

                override fun onAuthenticationFailed() { }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { }
            },
        )

        val spaceName = "Space 0 · Personal"
        val virtualRoot = VirtualStorage.spaceDir(this, 0).absolutePath

        setContent {
            PrivvyTheme {
                Surface(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    if (locked) {
                        LockScreen(
                            hasPin = AuthManager.hasPin(this),
                            biometricAvailable = AuthManager.canUseBiometric(this),
                            onSetPin = { AuthManager.setPin(this, it) },
                            onVerifyPin = { AuthManager.verifyPin(this, it) },
                            onUnlocked = { unlock() },
                            onBiometricRequest = { showBiometricPrompt() },
                        )
                    } else {
                        val importLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument(),
                        ) { uri ->
                            if (uri != null) importApk(uri)
                        }
                        HomeScreen(
                            spaceName = spaceName,
                            virtualRoot = virtualRoot,
                            apps = apps,
                            importing = importing,
                            importMessage = importMessage,
                            onLock = { lockSession() },
                            onImportApk = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "application/zip",
                                        "*/*",
                                    ),
                                )
                            },
                            onImportDebugApk = if (BuildConfig.DEBUG) ({ importDebugApk() }) else null,
                            onLaunchApp = { app -> launchApp(app) },
                        )
                    }
                }
            }
        }
    }

    private fun unlock() {
        if (!locked) return
        locked = false
        requestNotificationThenStart()
    }

    private fun requestNotificationThenStart() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission && !notificationPermissionAsked) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSessionService()
        }
    }

    private fun startSessionService() {
        val intent = Intent(this, SessionGuardService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSessionService() {
        stopService(Intent(this, SessionGuardService::class.java))
    }

    private fun lockSession() {
        stopSessionService()
        locked = true
    }

    private fun showBiometricPrompt() {
        if (!locked) return
        val status = AuthManager.biometricStatus(this)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) return

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Privvy")
            .setSubtitle("Use your biometric to open the space")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Use PIN")
            .build()
        biometricPrompt.authenticate(info)
    }

    private fun importApk(uri: android.net.Uri) {
        importing = true
        importMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { GuestApkImporter.importFromUri(this@MainActivity, uri, 0) }
            withContext(Dispatchers.Main) {
                importing = false
                result.onSuccess { app ->
                    apps.removeAll { it.packageName == app.packageName }
                    apps.add(app)
                    importMessage = "Imported ${app.label}"
                }.onFailure { t ->
                    importMessage = "Import failed: ${t.message}"
                    Log.e("PrivvyImport", "import failed", t)
                }
            }
        }
    }

    private fun importDebugApk() {
        val staged = File(filesDir, "import/guest-test-app.apk")
        if (!staged.exists()) {
            importMessage = "Debug APK not found at ${staged.absolutePath}. Push it with run-as first."
            return
        }
        importing = true
        importMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { GuestApkImporter.importFromFile(this@MainActivity, staged, 0) }
            withContext(Dispatchers.Main) {
                importing = false
                result.onSuccess { app ->
                    apps.removeAll { it.packageName == app.packageName }
                    apps.add(app)
                    importMessage = "Imported ${app.label}"
                }.onFailure { t ->
                    importMessage = "Import failed: ${t.message}"
                    Log.e("PrivvyImport", "import failed", t)
                }
            }
        }
    }

    private fun launchApp(app: InstalledApp) {
        try {
            VirtualAppLauncher.launch(this, 0, app)
        } catch (t: Throwable) {
            importMessage = "Launch failed: ${t.message}"
            Log.e("PrivvyLaunch", "launch failed", t)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopSessionService()
        }
        super.onDestroy()
    }
}
