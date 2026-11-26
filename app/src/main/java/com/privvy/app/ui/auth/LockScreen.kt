package com.privvy.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privvy.app.security.AuthManager
import com.privvy.app.ui.theme.PrivvySpacing

private enum class LockMode { SETUP, CONFIRM, UNLOCK }

@Composable
fun LockScreen(
    hasPin: Boolean,
    biometricAvailable: Boolean,
    onSetPin: (String) -> Boolean,
    onVerifyPin: (String, Int?) -> Int?,
    onUnlocked: (Int) -> Unit,
    onBiometricRequest: () -> Unit,
    setupSpaceId: Int = 0,
    requiredSpaceId: Int? = null,
    requiredSpaceName: String? = null,
    modifier: Modifier = Modifier,
) {
    var mode by remember(hasPin) { mutableStateOf(if (hasPin) LockMode.UNLOCK else LockMode.SETUP) }
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetEntry() {
        pin = ""
        error = null
    }

    fun submit() {
        when (mode) {
            LockMode.SETUP -> {
                if (!AuthManager.isValidPinFormat(pin)) {
                    error = "PIN must be ${AuthManager.MIN_PIN_LENGTH}–${AuthManager.MAX_PIN_LENGTH} digits"
                } else {
                    firstPin = pin
                    resetEntry()
                    mode = LockMode.CONFIRM
                }
            }
            LockMode.CONFIRM -> {
                if (pin == firstPin) {
                    if (onSetPin(pin)) {
                        resetEntry()
                        onUnlocked(setupSpaceId)
                    } else {
                        error = "Could not store PIN"
                    }
                } else {
                    error = "PINs do not match"
                    resetEntry()
                    firstPin = null
                    mode = LockMode.SETUP
                }
            }
            LockMode.UNLOCK -> {
                val spaceId = onVerifyPin(pin, requiredSpaceId)
                if (spaceId != null) {
                    resetEntry()
                    onUnlocked(spaceId)
                } else {
                    resetEntry()
                    error = "Wrong PIN"
                }
            }
        }
    }

    LaunchedEffect(mode, biometricAvailable) {
        if (mode == LockMode.UNLOCK && biometricAvailable) {
            onBiometricRequest()
        }
    }

    val title = when (mode) {
        LockMode.SETUP -> "Create a PIN to protect this space"
        LockMode.CONFIRM -> "Confirm your PIN"
        LockMode.UNLOCK -> requiredSpaceName?.let { "Enter PIN for $it" } ?: "Enter your PIN to unlock"
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        if (maxWidth >= 720.dp) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(18.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Privvy",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "A separate place for your apps and files.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
                PinEntry(
                    mode = mode,
                    title = title,
                    pin = pin,
                    error = error,
                    biometricAvailable = biometricAvailable,
                    onDigit = {
                        if (pin.length < AuthManager.MAX_PIN_LENGTH) {
                            pin += it
                            error = null
                        }
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                    onSubmit = { submit() },
                    onBiometricRequest = onBiometricRequest,
                    modifier = Modifier
                        .width(420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PrivvySpacing.screen, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Text(
                            text = "Privvy",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    PinEntry(
                        mode = mode,
                        title = title,
                        pin = pin,
                        error = error,
                        biometricAvailable = biometricAvailable,
                        onDigit = {
                            if (pin.length < AuthManager.MAX_PIN_LENGTH) {
                                pin += it
                                error = null
                            }
                        },
                        onBackspace = { pin = pin.dropLast(1) },
                        onSubmit = { submit() },
                        onBiometricRequest = onBiometricRequest,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinEntry(
    mode: LockMode,
    title: String,
    pin: String,
    error: String?,
    biometricAvailable: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBiometricRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when (mode) {
                LockMode.SETUP -> "Choose 4–8 digits you can remember."
                LockMode.CONFIRM -> "Enter the same PIN again."
                LockMode.UNLOCK -> "Use your PIN to open this space."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (pin.isEmpty()) "• • • •" else "●".repeat(pin.length),
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 10.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
                    .testTag("pin_display"),
            )
        }

        if (error != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        PinPad(
            onDigit = onDigit,
            onBackspace = onBackspace,
        )

        Button(
            onClick = onSubmit,
            enabled = pin.length >= AuthManager.MIN_PIN_LENGTH,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("pin_submit"),
        ) {
            Text(
                when (mode) {
                    LockMode.SETUP -> "Continue"
                    LockMode.CONFIRM -> "Set PIN"
                    LockMode.UNLOCK -> "Unlock"
                }
            )
        }

        if (mode == LockMode.UNLOCK && biometricAvailable) {
            TextButton(
                onClick = onBiometricRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlock with biometrics")
            }
        }
    }
}

@Composable
private fun PinPad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "backspace"),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { key ->
                    Box(Modifier.weight(1f)) {
                        when (key) {
                            "" -> Spacer(Modifier.size(60.dp))
                            "backspace" -> OutlinedButton(
                                onClick = onBackspace,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Delete last digit",
                                )
                            }
                            else -> FilledTonalButton(
                                onClick = { onDigit(key) },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                            ) {
                                Text(key, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
