package com.privvy.app.ui.auth

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
import androidx.compose.material3.Button
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
    onVerifyPin: (String) -> Boolean,
    onUnlocked: () -> Unit,
    onBiometricRequest: () -> Unit,
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
                        onUnlocked()
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
                if (onVerifyPin(pin)) {
                    resetEntry()
                    onUnlocked()
                } else {
                    error = "Wrong PIN"
                    resetEntry()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
                LockMode.UNLOCK -> "Enter your PIN to unlock"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = if (pin.isEmpty()) "• • • •" else "●".repeat(pin.length),
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 6.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp)
                    .testTag("pin_display"),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = error ?: " ",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

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
            modifier = Modifier
                .fillMaxWidth()
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    Box(Modifier.weight(1f)) {
                        when (key) {
                            "" -> Spacer(Modifier.size(64.dp))
                            "⌫" -> OutlinedButton(
                                onClick = onBackspace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) { Text(key, fontSize = 20.sp) }
                            else -> Button(
                                onClick = { onDigit(key) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) { Text(key, fontSize = 20.sp) }
                        }
                    }
                }
            }
        }
    }
}
