package com.privvy.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privvy.app.security.AuthManager

private enum class LockMode { SETUP, CONFIRM, UNLOCK }

/**
 * PIN setup/unlock screen. Biometric is offered when enrolled; PIN remains the
 * fallback so the app is never inaccessible on devices without biometrics.
 */
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

    // Offer biometrics once when the unlock screen appears.
    LaunchedEffect(mode, biometricAvailable) {
        if (mode == LockMode.UNLOCK && biometricAvailable) {
            onBiometricRequest()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(18.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "PRIVATE WORKSPACE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Privvy",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (mode) {
                    LockMode.SETUP -> "Create a PIN to protect this space"
                    LockMode.CONFIRM -> "Confirm your PIN"
                    LockMode.UNLOCK -> requiredSpaceName?.let { "Enter PIN for $it" } ?: "Enter your PIN to unlock"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (pin.isEmpty()) "• • • •" else "●".repeat(pin.length),
                    fontSize = 30.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 8.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp)
                        .testTag("pin_display"),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (error != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            PinPad(
                onDigit = {
                    if (pin.length < AuthManager.MAX_PIN_LENGTH) {
                        pin += it
                        error = null
                    }
                },
                onBackspace = { pin = pin.dropLast(1) },
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = pin.length >= AuthManager.MIN_PIN_LENGTH,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
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
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBiometricRequest) {
                    Text("Unlock with biometrics")
                }
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
        listOf("", "0", "⌫"),
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
                            "⌫" -> OutlinedButton(
                                onClick = onBackspace,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                            ) { Text(key, fontSize = 20.sp) }
                            else -> FilledTonalButton(
                                onClick = { onDigit(key) },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                            ) { Text(key, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
        }
    }
}
