package com.privvy.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.privvy.app.core.VirtualStorage
import com.privvy.app.security.AuthManager
import com.privvy.app.security.SessionGuardService
import com.privvy.app.ui.auth.LockScreen
import com.privvy.app.ui.home.HomeScreen
import com.privvy.app.ui.theme.PrivvyTheme
import java.util.concurrent.Executor

/**
 * Single entry point (no disguise). The app is locked until the user passes
 * biometric or PIN authentication; unlocking starts the foreground session
 * notification, and locking/closing stops it.
 */
class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(true)
    private var notificationPermissionAsked = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Start regardless: a denied permission hides the drawer notification,
        // but the foreground session still exists (visible in the FGS task manager).
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

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this) as Executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open; the user can retry.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or locked out — PIN remains available.
                }
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
                        HomeScreen(
                            spaceName = spaceName,
                            virtualRoot = virtualRoot,
                            onLock = { lockSession() },
                            onImportApk = { /* Importer lands next (Milestone 1). */ },
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

    override fun onDestroy() {
        if (isFinishing) {
            stopSessionService()
        }
        super.onDestroy()
    }
}
