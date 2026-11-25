package com.privvy.app.ui

import android.Manifest
import android.app.AlertDialog
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.privvy.app.BuildConfig
import com.privvy.app.core.FileSpaceRepository
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
import com.privvy.app.ui.auth.LockScreen
import com.privvy.app.ui.files.VaultFilesScreen
import com.privvy.app.ui.home.HomeScreen
import com.privvy.app.ui.spaces.SpaceManagerScreen
import com.privvy.app.ui.theme.PrivvyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : FragmentActivity() {

    private enum class Screen { HOME, SPACES, VAULT }

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
    private var pendingExport by mutableStateOf<VaultFile?>(null)
    private var pendingExportEpoch = 0L
    private var vaultPickerEpoch = 0L
    private var vaultPickerSpaceId = -1
    private var vaultJob: Job? = null
    private var systemPickerActive = false
    private var permissionsDialog: AlertDialog? = null

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
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let(Screen::valueOf) ?: Screen.HOME
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

    @androidx.compose.runtime.Composable
    private fun Content() {
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
                    onManageSpaces = { managerMessage = null; screen = Screen.SPACES },
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
                onClose = { screen = Screen.HOME },
                onCreateSpace = ::createSpace,
                onRenameSpace = ::renameSpace,
                onWipeSpace = ::wipeSpace,
                onDeleteSpace = ::deleteSpace,
                onChangePin = ::changePin,
            )
            Screen.VAULT -> VaultFilesScreen(
                files = vaultFiles,
                message = vaultMessage,
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
        permissionsDialog?.dismiss()
        sessionEpoch++
        locked = true
        importing = false
        if (!keepRequired) requiredSpaceId = null
        screen = Screen.HOME
        apps.clear()
        vaultFiles = emptyList()
        importMessage = null
        pendingExport = null
        pendingExportEpoch = 0L
        vaultPickerEpoch = 0L
        vaultPickerSpaceId = -1
        vaultJob?.cancel()
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

    private fun importApk(uri: Uri) {
        runAppImport("Imported") { spaceId ->
            GuestApkImporter.importFromUri(this@MainActivity, uri, spaceId)
        }
    }

    private fun showInstalledAppPicker() {
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .toList()
        if (installed.isEmpty()) {
            importMessage = "No launchable installed apps are visible"
            return
        }
        val labels = installed.map { "${it.loadLabel(packageManager)}\n${it.packageName}" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Clone installed app")
            .setItems(labels) { _, index -> cloneInstalled(installed[index].packageName) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cloneInstalled(packageName: String) {
        runAppImport("Cloned") { spaceId ->
            GuestApkImporter.importInstalled(this@MainActivity, packageName, spaceId)
        }
    }

    private fun importDebugApk() {
        val staged = File(filesDir, "import/guest-test-app.apk")
        if (!staged.exists()) {
            importMessage = "Debug APK not found at ${staged.absolutePath}. Push it with run-as first."
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
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { operation(spaceId) }
            withContext(Dispatchers.Main) {
                if (epoch != sessionEpoch || spaceId != activeSpaceId) return@withContext
                importing = false
                result.onSuccess { app ->
                    apps.removeAll { it.packageName == app.packageName }
                    apps.add(app)
                    importMessage = "$successVerb ${app.label}"
                    com.privvy.app.engine.GuestReceiverRegistrar.refresh(this@MainActivity)
                }.onFailure { error ->
                    importMessage = "Import failed: ${error.message}"
                    Log.e("PrivvyImport", "import failed", error)
                }
            }
        }
    }

    private fun launchApp(app: InstalledApp) {
        runCatching { VirtualAppLauncher.launch(this, activeSpaceId, app) }
            .onFailure { error -> importMessage = "Launch failed: ${error.message}" }
    }

    private fun showPermissionManager(app: InstalledApp) {
        val permissions = GuestPermissionStore.supportedRuntimePermissions(this, app.requestedPermissions)
        if (permissions.isEmpty()) {
            importMessage = "${app.label} has no manageable runtime permissions"
            return
        }
        val labels = permissions.map { permission ->
            val label = runCatching {
                packageManager.getPermissionInfo(permission, 0).loadLabel(packageManager).toString()
            }.getOrDefault(permission.substringAfterLast('.'))
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                label
            } else {
                "$label (Android permission not granted)"
            }
        }.toTypedArray()
        val checked = permissions.map {
            GuestPermissionStore.isGranted(this, activeSpaceId, app.packageName, it)
        }.toBooleanArray()
        permissionsDialog = AlertDialog.Builder(this)
            .setTitle("Permissions for ${app.label}")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val saved = runCatching {
                    permissions.forEachIndexed { index, permission ->
                        GuestPermissionStore.setGranted(
                            this,
                            activeSpaceId,
                            app.packageName,
                            permission,
                            checked[index],
                        )
                    }
                }.isSuccess
                importMessage = if (saved) {
                    "Permissions updated for ${app.label}"
                } else {
                    "Could not update permissions for ${app.label}"
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { permissionsDialog = null }
                dialog.show()
            }
    }

    private fun removeApp(app: InstalledApp) {
        runCatching {
            GuestPermissionStore.clear(this, activeSpaceId, app.packageName)
            SpaceAppRegistry.remove(this, activeSpaceId, app.packageName)
            VirtualEngine.clearGuestClassLoader(app.packageName)
        }.onSuccess {
            apps.removeAll { it.packageName == app.packageName }
            importMessage = "Removed ${app.label} from this space"
        }.onFailure {
            importMessage = "Could not remove ${app.label}: ${it.message}"
        }
    }

    private fun createSpace(name: String, pin: String) {
        runCatching { repository.createSpace(name, pin.toCharArray()) }
            .onSuccess { spaces = repository.listSpaces(); managerMessage = "Created ${it.name}" }
            .onFailure { managerMessage = it.message ?: "Could not create space" }
    }

    private fun renameSpace(id: Int, name: String) {
        runCatching { repository.renameSpace(id, name) }
            .onSuccess { spaces = repository.listSpaces(); managerMessage = "Space renamed" }
            .onFailure { managerMessage = it.message ?: "Could not rename space" }
    }

    private fun changePin(id: Int, pin: String) {
        runCatching { repository.setPin(id, pin) }
            .onSuccess { spaces = repository.listSpaces(); managerMessage = "PIN updated" }
            .onFailure { managerMessage = it.message ?: "Could not update PIN" }
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
            managerMessage = "Space wiped"
        }.onFailure { managerMessage = it.message ?: "Could not wipe space" }
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
            managerMessage = "Space deleted"
        }.onFailure { managerMessage = it.message ?: "Could not delete space" }
    }

    private fun refreshVault() {
        val result = runCatching { VaultCrypto.list(this, activeSpaceId) }
        result.onSuccess { vaultFiles = it }.onFailure {
            vaultMessage = "Could not open vault: ${it.message}"
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
                vaultMessage = result.fold(
                    onSuccess = { "Imported ${it.displayName}" },
                    onFailure = {
                        Log.e("PrivvyVault", "vault import failed", it)
                        "Import failed: ${it.message ?: it.javaClass.simpleName}"
                    },
                )
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
        vaultMessage = if (deleted) "Deleted ${file.displayName}" else "Could not delete file"
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
                vaultMessage = if (result.getOrDefault(false)) "Exported ${file.displayName}" else "Export failed"
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
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.files", target) to file.mimeType
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
                        startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }.onFailure {
                        preview?.delete()
                        vaultMessage = "No app can open ${file.mimeType}"
                    }
                }.onFailure {
                    preview?.delete()
                    vaultMessage = "Could not decrypt ${file.displayName}"
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
        outState.putString(KEY_SCREEN, screen.name)
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
