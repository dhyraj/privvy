package com.privvy.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.privvy.app.BuildConfig
import com.privvy.app.core.FileSpaceRepository
import com.privvy.app.core.GlobalCleanup
import com.privvy.app.core.InstalledApp
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.core.VirtualSpace
import com.privvy.app.core.VirtualStorage
import com.privvy.app.engine.GuestApkImporter
import com.privvy.app.engine.GuestPermissionStore
import com.privvy.app.engine.VirtualAppLauncher
import com.privvy.app.engine.VirtualEngine
import com.privvy.app.security.AuthManager
import com.privvy.app.security.GuestDatabaseCrypto
import com.privvy.app.security.SessionGuardService
import com.privvy.app.security.SessionLockManager
import com.privvy.app.security.VaultCrypto
import com.privvy.app.security.VaultFile
import com.privvy.app.ui.apps.CloneAppOption
import com.privvy.app.ui.apps.CloneAppsScreen
import com.privvy.app.ui.apps.PermissionChoice
import com.privvy.app.ui.apps.PermissionManagerDialog
import com.privvy.app.ui.auth.LockScreen
import com.privvy.app.ui.files.VaultFilesScreen
import com.privvy.app.ui.files.vaultOpenMimeType
import com.privvy.app.ui.home.HomeScreen
import com.privvy.app.ui.navigation.PrivvyDestination
import com.privvy.app.ui.navigation.PrivvyNavigationScaffold
import com.privvy.app.ui.spaces.SpaceManagerScreen
import com.privvy.app.ui.theme.PrivvyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : FragmentActivity() {

    private enum class Screen { HOME, SPACES, VAULT, CLONE_APPS }

    private lateinit var repository: FileSpaceRepository
    private var spaces by mutableStateOf(emptyList<VirtualSpace>())
    private var activeSpaceId by mutableStateOf(0)
    private var requiredSpaceId by mutableStateOf<Int?>(null)
    private var setupSpaceId by mutableStateOf(0)
    private var screen by mutableStateOf(Screen.HOME)
    private var locked by mutableStateOf(true)
    private var notificationPermissionAsked = false
    private var lastTouchSent = 0L
    private var sessionEpoch = 0L

    private val apps = mutableStateListOf<InstalledApp>()
    private var vaultFiles by mutableStateOf(emptyList<VaultFile>())
    private var importing by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var importMessage by mutableStateOf<String?>(null)
    private var managerMessage by mutableStateOf<String?>(null)
    private var vaultMessage by mutableStateOf<String?>(null)
    private var importFeedbackVersion by mutableStateOf(0)
    private var managerFeedbackVersion by mutableStateOf(0)
    private var vaultFeedbackVersion by mutableStateOf(0)
    private var vaultLoadFailed by mutableStateOf(false)
    private var pendingExport by mutableStateOf<VaultFile?>(null)
    private var pendingExportEpoch = 0L
    private var vaultPickerEpoch = 0L
    private var vaultPickerSpaceId = -1
    private var vaultJob: Job? = null
    private var importJob: Job? = null
    private var cleanupInProgress by mutableStateOf(false)
    private var cleanupFailed by mutableStateOf(false)
    private var systemPickerActive = false
    private var installedApps by mutableStateOf(emptyList<CloneAppOption>())
    private var installedAppsLoading by mutableStateOf(false)
    private var installedAppsError by mutableStateOf<String?>(null)
    private var installedAppsJob: Job? = null
    private var installedAppsRequest = 0L
    private var permissionApp by mutableStateOf<InstalledApp?>(null)
    private var permissionChoices by mutableStateOf(emptyList<PermissionChoice>())

    private lateinit var biometricPrompt: BiometricPrompt

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SessionLockManager.ACTION_SESSION_LOCKED) {
                applyLock(keepRequired = requiredSpaceId != null)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { startSessionService() }

    private val vaultPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        systemPickerActive = false
        val epoch = vaultPickerEpoch
        val spaceId = vaultPickerSpaceId
        vaultPickerEpoch = 0L
        vaultPickerSpaceId = -1
        if (uri != null && !locked && epoch == sessionEpoch && spaceId == activeSpaceId) {
            importVaultFile(uri, epoch, spaceId)
        }
    }

    private val vaultExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        systemPickerActive = false
        val file = pendingExport
        val epoch = pendingExportEpoch
        pendingExport = null
        pendingExportEpoch = 0L
        if (uri != null && file != null && !locked && epoch == sessionEpoch && file.spaceId == activeSpaceId) {
            exportVaultFile(file, uri, epoch)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        repository = FileSpaceRepository(this)
        spaces = repository.listSpaces()
        activeSpaceId = SessionLockManager.activeSpaceId(this).takeIf { id -> spaces.any { it.id == id } } ?: 0
        setupSpaceId = spaces.first().id
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?.let(Screen::valueOf)
            ?.takeUnless { it == Screen.CLONE_APPS }
            ?: Screen.HOME
        ContextCompat.registerReceiver(
            this,
            lockReceiver,
            android.content.IntentFilter(SessionLockManager.ACTION_SESSION_LOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock(requiredSpaceId ?: activeSpaceId)
                }
            },
        )
        setContent {
            PrivvyTheme {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                ) { Content() }
            }
        }
    }

    @Composable
    private fun Content() {
        if (cleanupInProgress) CleanupProgressDialog()
        if (cleanupFailed) CleanupFailureDialog(
            onDismiss = { cleanupFailed = false },
            onRetry = {
                cleanupFailed = false
                cleanUpPrivvy()
            },
        )
        if (locked) {
            LockScreen(
                hasPin = spaces.any { it.pinHashHex != null },
                biometricAvailable = AuthManager.canUseBiometric(this),
                onSetPin = { pin -> setSpacePin(pin) },
                onVerifyPin = { pin, required ->
                    if (required != null) required.takeIf { repository.verifyPin(it, pin) }
                    else repository.findSpaceByPin(pin)?.id
                },
                onUnlocked = ::unlock,
                onBiometricRequest = ::showBiometricPrompt,
                setupSpaceId = setupSpaceId,
                requiredSpaceId = requiredSpaceId,
                requiredSpaceName = spaces.firstOrNull { it.id == requiredSpaceId }?.name,
            )
            return
        }
        val currentDestination = when (screen) {
            Screen.SPACES -> PrivvyDestination.SPACES
            Screen.VAULT -> PrivvyDestination.VAULT
            else -> PrivvyDestination.HOME
        }
        PrivvyNavigationScaffold(
            current = currentDestination,
            onNavigate = { destination ->
                if (screen == Screen.CLONE_APPS) dismissInstalledAppPicker()
                when (destination) {
                    PrivvyDestination.HOME -> screen = Screen.HOME
                    PrivvyDestination.SPACES -> {
                        managerMessage = null
                        screen = Screen.SPACES
                    }
                    PrivvyDestination.VAULT -> {
                        vaultMessage = null
                        refreshVault()
                        screen = Screen.VAULT
                    }
                }
            },
        ) { contentPadding ->
            Box(Modifier.padding(contentPadding)) {
                when (screen) {
                    Screen.HOME -> {
                        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                            systemPickerActive = false
                            if (uri != null) importApk(uri)
                        }
                        HomeScreen(
                            spaceName = spaces.firstOrNull { it.id == activeSpaceId }?.name ?: "Space $activeSpaceId",
                            spaces = spaces,
                            activeSpaceId = activeSpaceId,
                            apps = apps,
                            importing = importing,
                            importMessage = importMessage,
                            feedbackVersion = importFeedbackVersion,
                            onLock = { lockSession() },
                            onImportApk = {
                                systemPickerActive = true
                                importLauncher.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "application/zip",
                                        "*/*",
                                    ),
                                )
                            },
                            onCloneInstalled = ::showInstalledAppPicker,
                            onImportDebugApk = if (BuildConfig.DEBUG) ({ importDebugApk() }) else null,
                            onLaunchApp = ::launchApp,
                            onManagePermissions = ::showPermissionManager,
                            onOpenVault = { vaultMessage = null; refreshVault(); screen = Screen.VAULT },
                            onSwitchSpace = ::switchSpace,
                            onRemoveApp = ::removeApp,
                            onEnableReturnPill = if (overlayGranted) null else {
                                {
                                    runCatching {
                                        startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                android.net.Uri.parse("package:$packageName"),
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    Screen.SPACES -> SpaceManagerScreen(
                        spaces = spaces,
                        activeSpaceId = activeSpaceId,
                        message = managerMessage,
                        feedbackVersion = managerFeedbackVersion,
                        cleanupInProgress = cleanupInProgress,
                        onClose = { screen = Screen.HOME },
                        onCreateSpace = ::createSpace,
                        onRenameSpace = ::renameSpace,
                        onWipeSpace = ::wipeSpace,
                        onDeleteSpace = ::deleteSpace,
                        onChangePin = ::changePin,
                        onCleanup = ::cleanUpPrivvy,
                    )
                    Screen.VAULT -> VaultFilesScreen(
                        files = vaultFiles,
                        message = vaultMessage,
                        feedbackVersion = vaultFeedbackVersion,
                        loadFailed = vaultLoadFailed,
                        onRetry = ::refreshVault,
                        onBack = { screen = Screen.HOME },
                        onImportFile = {
                            systemPickerActive = true
                            vaultPickerEpoch = sessionEpoch
                            vaultPickerSpaceId = activeSpaceId
                            vaultPicker.launch(arrayOf("*/*"))
                        },
                        onOpenFile = ::openVaultFile,
                        onExportFile = {
                            systemPickerActive = true
                            pendingExport = it
                            pendingExportEpoch = sessionEpoch
                            vaultExporter.launch(it.displayName)
                        },
                        onDeleteFile = ::deleteVaultFile,
                    )
                    Screen.CLONE_APPS -> CloneAppsScreen(
                        apps = installedApps,
                        loading = installedAppsLoading,
                        errorMessage = installedAppsError,
                        onRetry = ::showInstalledAppPicker,
                        onBack = ::dismissInstalledAppPicker,
                        onClone = { app ->
                            dismissInstalledAppPicker()
                            cloneInstalled(app.packageName)
                        },
                    )
                }
            }
        }

        permissionApp?.let { app ->
            PermissionManagerDialog(
                app = app,
                choices = permissionChoices,
                onDismiss = {
                    permissionApp = null
                    permissionChoices = emptyList()
                },
                onSave = { updated ->
                    savePermissions(app, updated)
                    permissionApp = null
                    permissionChoices = emptyList()
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        ReturnPill.hide(this)
        if (!locked) notifySessionGuard(SessionGuardService.ACTION_FOREGROUND)
    }

    override fun onStop() {
        super.onStop()
        if (!locked && !systemPickerActive) notifySessionGuard(SessionGuardService.ACTION_BACKGROUND)
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN && !locked) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTouchSent > 30_000) {
                lastTouchSent = now
                notifySessionGuard(SessionGuardService.ACTION_TOUCH)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun notifySessionGuard(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SessionGuardService::class.java).setAction(action),
        )
    }

    private fun setSpacePin(pin: String): Boolean = runCatching {
        repository.setPin(setupSpaceId, pin)
        spaces = repository.listSpaces()
    }.isSuccess

    private fun unlock(spaceId: Int) {
        if (locked) {
            sessionEpoch++
            activeSpaceId = spaceId
            requiredSpaceId = null
            locked = false
            SessionLockManager.beginSession(this, spaceId)
            loadApps()
            refreshVault()
            screen = Screen.HOME
            requestNotificationThenStart()
        }
    }

    private fun lockSession() {
        SessionLockManager.lockSession(this)
        stopSessionService()
        applyLock()
    }

    private fun switchSpace(spaceId: Int) {
        if (spaceId == activeSpaceId) return
        requiredSpaceId = spaceId
        SessionLockManager.lockSession(this)
        stopSessionService()
        applyLock(keepRequired = true)
    }

    private fun applyLock(keepRequired: Boolean = false) {
        permissionApp = null
        permissionChoices = emptyList()
        installedAppsRequest++
        installedAppsJob?.cancel()
        installedAppsJob = null
        installedApps = emptyList()
        installedAppsLoading = false
        installedAppsError = null
        sessionEpoch++
        locked = true
        importing = false
        if (!keepRequired) requiredSpaceId = null
        screen = Screen.HOME
        apps.clear()
        vaultFiles = emptyList()
        vaultMessage = null
        vaultLoadFailed = false
        importMessage = null
        pendingExport = null
        pendingExportEpoch = 0L
        vaultPickerEpoch = 0L
        vaultPickerSpaceId = -1
        systemPickerActive = false
        vaultJob?.cancel()
        importJob?.cancel()
        importJob = null
        clearVaultPreviews()
    }

    private fun clearVaultPreviews() {
        File(cacheDir, "vault-preview").deleteRecursively()
        File(cacheDir, "vault-decrypted").deleteRecursively()
    }

    private fun requestNotificationThenStart() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && !notificationPermissionAsked) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSessionService()
        }
    }

    private fun startSessionService() {
        if (!locked) ContextCompat.startForegroundService(this, Intent(this, SessionGuardService::class.java))
    }

    private fun stopSessionService() {
        stopService(Intent(this, SessionGuardService::class.java))
    }

    private fun showBiometricPrompt() {
        if (!locked || AuthManager.biometricStatus(this) != BiometricManager.BIOMETRIC_SUCCESS) return
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Privvy")
                .setSubtitle("Use your biometric to open the space")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Use PIN")
                .build(),
        )
    }

    private fun loadApps() {
        apps.clear()
        apps.addAll(SpaceAppRegistry.list(this, activeSpaceId))
    }

    private fun setImportFeedback(message: String) {
        importMessage = message
        importFeedbackVersion++
    }

    private fun setManagerFeedback(message: String) {
        managerMessage = message
        managerFeedbackVersion++
    }

    private fun setVaultFeedback(message: String) {
        vaultMessage = message
        vaultFeedbackVersion++
    }

    private fun importApk(uri: Uri) {
        runAppImport("Imported") { spaceId ->
            GuestApkImporter.importFromUri(this@MainActivity, uri, spaceId)
        }
    }

    private fun showInstalledAppPicker() {
        val epoch = sessionEpoch
        val request = ++installedAppsRequest
        installedAppsJob?.cancel()
        installedApps = emptyList()
        installedAppsLoading = true
        installedAppsError = null
        screen = Screen.CLONE_APPS
        installedAppsJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                        .asSequence()
                        .filter {
                            ensureActive()
                            it.packageName != packageName &&
                                packageManager.getLaunchIntentForPackage(it.packageName) != null
                        }
                        .mapNotNull { info ->
                            ensureActive()
                            val label = info.loadLabel(packageManager).toString()
                                .takeUnless { it.isBlank() || it == info.packageName }
                                ?: return@mapNotNull null
                            val icon = runCatching {
                                info.loadIcon(packageManager).toBitmap(160, 160)
                            }.getOrNull() ?: return@mapNotNull null
                            CloneAppOption(
                                packageName = info.packageName,
                                label = label,
                                icon = icon,
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                        .toList()
                }
            }
            ensureActive()
            if (request != installedAppsRequest || epoch != sessionEpoch || locked ||
                screen != Screen.CLONE_APPS
            ) return@launch
            installedAppsLoading = false
            installedAppsJob = null
            result.onSuccess { installedApps = it }
                .onFailure { error ->
                    installedAppsError = "Could not load installed apps. Try again."
                    Log.e("PrivvyClone", "could not load installed apps", error)
                }
        }
    }

    private fun dismissInstalledAppPicker() {
        installedAppsRequest++
        installedAppsJob?.cancel()
        installedAppsJob = null
        installedApps = emptyList()
        installedAppsLoading = false
        installedAppsError = null
        screen = Screen.HOME
    }

    private fun cloneInstalled(packageName: String) {
        runAppImport("Cloned") { spaceId ->
            GuestApkImporter.importInstalled(this@MainActivity, packageName, spaceId)
        }
    }

    private fun importDebugApk() {
        val staged = File(filesDir, "import/guest-test-app.apk")
        if (!staged.exists()) {
            setImportFeedback("The test app is not available on this device.")
            return
        }
        runAppImport("Imported") { spaceId ->
            GuestApkImporter.importFromFile(this@MainActivity, staged, spaceId)
        }
    }

    private fun runAppImport(successVerb: String, operation: (Int) -> InstalledApp) {
        if (locked || importing) return
        val spaceId = activeSpaceId
        val epoch = sessionEpoch
        importing = true
        importMessage = null
        importJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { operation(spaceId) }
            withContext(Dispatchers.Main) {
                if (epoch != sessionEpoch || spaceId != activeSpaceId) return@withContext
                importJob = null
                importing = false
                result.onSuccess { app ->
                    apps.removeAll { it.packageName == app.packageName }
                    apps.add(app)
                    setImportFeedback("$successVerb ${app.label}")
                    com.privvy.app.engine.GuestReceiverRegistrar.refresh(this@MainActivity)
                }.onFailure { error ->
                    setImportFeedback("Could not import app. Try again.")
                    Log.e("PrivvyImport", "import failed", error)
                }
            }
        }
    }

    private fun launchApp(app: InstalledApp) {
        runCatching { VirtualAppLauncher.launch(this, activeSpaceId, app) }
            .onFailure { error ->
                setImportFeedback("Could not launch ${app.label}. Try again.")
                Log.e("PrivvyLaunch", "launch failed", error)
            }
    }

    private fun showPermissionManager(app: InstalledApp) {
        val permissions = GuestPermissionStore.supportedRuntimePermissions(this, app.requestedPermissions)
        if (permissions.isEmpty()) {
            setImportFeedback("${app.label} has no manageable runtime permissions")
            return
        }
        permissionChoices = permissions.map { permission ->
            PermissionChoice(
                permission = permission,
                label = runCatching {
                    packageManager.getPermissionInfo(permission, 0).loadLabel(packageManager).toString()
                }.getOrDefault(permission.substringAfterLast('.')),
                granted = GuestPermissionStore.isGranted(this, activeSpaceId, app.packageName, permission),
                androidGranted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
        permissionApp = app
    }

    private fun savePermissions(app: InstalledApp, choices: List<PermissionChoice>) {
        val saved = runCatching {
            choices.forEach { choice ->
                GuestPermissionStore.setGranted(
                    this,
                    activeSpaceId,
                    app.packageName,
                    choice.permission,
                    choice.granted,
                )
            }
        }.isSuccess
        setImportFeedback(
            if (saved) {
                "Permissions updated for ${app.label}"
            } else {
                "Could not update permissions for ${app.label}"
            },
        )
    }

    private fun removeApp(app: InstalledApp) {
        runCatching {
            GuestPermissionStore.clear(this, activeSpaceId, app.packageName)
            SpaceAppRegistry.remove(this, activeSpaceId, app.packageName)
            VirtualEngine.clearGuestClassLoader(app.packageName)
        }.onSuccess {
            apps.removeAll { it.packageName == app.packageName }
            setImportFeedback("Removed ${app.label} from this space")
        }.onFailure { error ->
            setImportFeedback("Could not remove ${app.label}. Try again.")
            Log.e("PrivvyApps", "remove failed", error)
        }
    }

    private fun createSpace(name: String, pin: String) {
        runCatching { repository.createSpace(name, pin.toCharArray()) }
            .onSuccess {
                spaces = repository.listSpaces()
                setManagerFeedback("Created ${it.name}")
            }
            .onFailure { setManagerFeedback("Could not create space. Try again.") }
    }

    private fun renameSpace(id: Int, name: String) {
        runCatching { repository.renameSpace(id, name) }
            .onSuccess {
                spaces = repository.listSpaces()
                setManagerFeedback("Space renamed")
            }
            .onFailure { setManagerFeedback("Could not rename space. Try again.") }
    }

    private fun changePin(id: Int, pin: String) {
        runCatching { repository.setPin(id, pin) }
            .onSuccess {
                spaces = repository.listSpaces()
                setManagerFeedback("PIN updated")
            }
            .onFailure { setManagerFeedback("Could not update PIN. Try again.") }
    }

    private fun wipeSpace(id: Int) {
        runCatching {
            SessionLockManager.killGuestProcesses(this)
            repository.wipeSpace(id)
            check(VaultCrypto.wipeSpace(this, id)) { "Could not remove encrypted vault data" }
            GuestDatabaseCrypto.wipeSpace(this, id)
        }.onSuccess {
            clearVaultPreviews()
            if (id == activeSpaceId) { loadApps(); refreshVault() }
            setManagerFeedback("Space wiped")
        }.onFailure { setManagerFeedback("Could not wipe space. Try again.") }
    }

    private fun deleteSpace(id: Int) {
        runCatching {
            check(VaultCrypto.wipeSpace(this, id)) { "Could not remove encrypted vault data" }
            GuestDatabaseCrypto.wipeSpace(this, id)
            repository.deleteSpace(id)
            spaces = repository.listSpaces()
            if (id == activeSpaceId) {
                activeSpaceId = spaces.first().id
                lockSession()
            }
            setManagerFeedback("Space deleted")
        }.onFailure { setManagerFeedback("Could not delete space. Try again.") }
    }

    /** Quiesces UI jobs, runs the non-cancellable reset, and keeps failures locked for retry. */
    private fun cleanUpPrivvy() {
        if (cleanupInProgress) return
        cleanupInProgress = true
        cleanupFailed = false
        locked = true
        sessionEpoch++
        lifecycleScope.launch {
            stopSessionService()
            val activeJobs = listOfNotNull(installedAppsJob, vaultJob, importJob)
            activeJobs.forEach { it.cancel() }
            activeJobs.joinAll()
            applyLock()
            val result = runCatching {
                withContext(NonCancellable + Dispatchers.IO) {
                    GlobalCleanup.reset(this@MainActivity)
                }
            }
            result.onSuccess { personal ->
                spaces = listOf(personal)
                activeSpaceId = personal.id
                setupSpaceId = personal.id
                requiredSpaceId = null
                cleanupInProgress = false
                applyLock()
            }.onFailure { error ->
                Log.e("PrivvyCleanup", "global cleanup failed", error)
                spaces = runCatching { repository.listSpaces() }.getOrDefault(spaces)
                setupSpaceId = spaces.firstOrNull()?.id ?: 0
                activeSpaceId = spaces.firstOrNull()?.id ?: 0
                requiredSpaceId = null
                cleanupInProgress = false
                cleanupFailed = true
                applyLock()
            }
        }
    }

    @Composable
    private fun CleanupProgressDialog() {
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("cleanup_progress_dialog"),
            title = { Text("Cleaning up Privvy") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Stopping guests and removing Privvy data. This may take a moment.")
                }
            },
            confirmButton = {},
        )
    }

    @Composable
    private fun CleanupFailureDialog(onDismiss: () -> Unit, onRetry: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("cleanup_failure_dialog"),
            title = { Text("Cleanup did not finish") },
            text = {
                Text(
                    "Privvy is locked and some data may already have been removed. " +
                        "Try cleanup again to finish starting fresh.",
                )
            },
            confirmButton = {
                TextButton(onClick = onRetry, modifier = Modifier.testTag("retry_cleanup")) {
                    Text("Try again")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            },
        )
    }

    private fun refreshVault() {
        val spaceId = activeSpaceId
        vaultMessage = null
        val result = runCatching { VaultCrypto.list(this, spaceId) }
        if (locked || spaceId != activeSpaceId) return
        vaultLoadFailed = false
        result.onSuccess { vaultFiles = it }
            .onFailure { error ->
                vaultFiles = emptyList()
                vaultLoadFailed = true
                setVaultFeedback("Could not open vault. Try again.")
                Log.e("PrivvyVault", "vault list failed", error)
            }
    }

    private fun importVaultFile(uri: Uri, epoch: Long, spaceId: Int) {
        vaultMessage = null
        vaultJob?.cancel()
        vaultJob = lifecycleScope.launch(Dispatchers.IO) {
            val temporary = File(cacheDir, "vault-import-${System.nanoTime()}")
            val result = runCatching {
                val metadata = documentMetadata(uri)
                contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> copyBounded(input, output) }
                } ?: throw IllegalStateException("Cannot open selected file")
                ensureActive()
                VaultCrypto.importFile(
                    this@MainActivity,
                    spaceId,
                    temporary,
                    displayName = metadata.first,
                    mimeType = metadata.second,
                    isAuthorized = { !locked && epoch == sessionEpoch && spaceId == activeSpaceId },
                )
            }
            temporary.delete()
            withContext(Dispatchers.Main) {
                if (epoch != sessionEpoch || spaceId != activeSpaceId) return@withContext
                refreshVault()
                if (!vaultLoadFailed) {
                    setVaultFeedback(
                        result.fold(
                            onSuccess = { "Imported ${it.displayName}" },
                            onFailure = {
                                Log.e("PrivvyVault", "vault import failed", it)
                                "Could not import file. Check that it is 64 MiB or smaller and try again."
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun documentMetadata(uri: Uri): Pair<String, String?> {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "Imported file"
        return name to contentResolver.getType(uri)
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 64L * 1024 * 1024) { "Vault files must be 64 MiB or smaller" }
            output.write(buffer, 0, count)
        }
    }

    private fun deleteVaultFile(file: VaultFile) {
        val deleted = runCatching { VaultCrypto.delete(this, file) }.getOrDefault(false)
        refreshVault()
        if (!vaultLoadFailed) {
            setVaultFeedback(
                if (deleted) "Deleted ${file.displayName}" else "Could not delete file. Try again.",
            )
        }
    }

    private fun exportVaultFile(file: VaultFile, uri: Uri, epoch: Long) {
        vaultJob?.cancel()
        vaultJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                VaultCrypto.exportTo(
                    this@MainActivity,
                    file,
                    uri,
                    isAuthorized = { !locked && epoch == sessionEpoch && file.spaceId == activeSpaceId },
                )
            }
            withContext(Dispatchers.Main) {
                if (epoch != sessionEpoch) return@withContext
                if (!vaultLoadFailed) {
                    setVaultFeedback(
                        if (result.getOrDefault(false)) {
                            "Exported ${file.displayName}"
                        } else {
                            "Could not export file. Try again."
                        },
                    )
                }
            }
        }
    }

    private fun openVaultFile(file: VaultFile) {
        if (locked) return
        val epoch = sessionEpoch
        vaultJob?.cancel()
        vaultJob = lifecycleScope.launch(Dispatchers.IO) {
            var preview: File? = null
            val result = runCatching {
                val plaintext = VaultCrypto.readBytes(this@MainActivity, file)
                try {
                    ensureActive()
                    check(!locked && epoch == sessionEpoch && file.spaceId == activeSpaceId)
                    val directory = File(cacheDir, "vault-preview").apply { check(mkdirs() || isDirectory) }
                    val safeName = file.displayName.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
                    check(!locked && epoch == sessionEpoch && file.spaceId == activeSpaceId)
                    val target = File(directory, "${file.id}-${System.nanoTime()}-$safeName")
                    preview = target
                    target.writeBytes(plaintext)
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.files", target) to vaultOpenMimeType(file)
                } finally {
                    plaintext.fill(0)
                }
            }
            withContext(NonCancellable + Dispatchers.Main) {
                if (epoch != sessionEpoch || locked) {
                    preview?.delete()
                    return@withContext
                }
                result.onSuccess { (uri, mimeType) ->
                    runCatching {
                        val viewIntent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(viewIntent, "Open with"))
                    }.onFailure {
                        preview?.delete()
                        if (!vaultLoadFailed) {
                            setVaultFeedback("No app on this device can open this file.")
                        }
                    }
                }.onFailure {
                    preview?.delete()
                    if (!vaultLoadFailed) {
                        setVaultFeedback("Could not decrypt ${file.displayName}")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = android.provider.Settings.canDrawOverlays(this)
        if (!locked && SessionLockManager.isLocked(this)) applyLock()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SCREEN, if (screen == Screen.CLONE_APPS) Screen.HOME.name else screen.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(lockReceiver) }
        if (isFinishing && !locked) SessionLockManager.lockSession(this)
        if (isFinishing) stopSessionService()
        super.onDestroy()
    }

    companion object {
        private const val KEY_SCREEN = "screen"
    }
}
