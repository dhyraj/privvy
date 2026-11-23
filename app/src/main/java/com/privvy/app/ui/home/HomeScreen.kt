package com.privvy.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privvy.app.io.PrivvyNative

/**
 * Real app home (no disguise). Shows the active session, the current space,
 * and the entry point for importing a guest app (the importer lands next).
 */
@Composable
fun HomeScreen(
    spaceName: String,
    virtualRoot: String,
    onLock: () -> Unit,
    onImportApk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Privvy",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            AssistChip(
                onClick = {},
                label = { Text("Session active") },
            )
        }
        Text(
            "Notification stays visible until you lock or close the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusRow("Space", spaceName)
                HorizontalDivider()
                StatusRow(
                    "Native IO hooks",
                    if (PrivvyNative.isLoaded) "Loaded" else "Unavailable",
                )
                HorizontalDivider()
                StatusRow("Guest apps", "None yet")
                HorizontalDivider()
                Column {
                    Text("Virtual storage", style = MaterialTheme.typography.labelMedium)
                    Text(
                        virtualRoot,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Button(
            onClick = onImportApk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import APK")
        }
        Text(
            "APKs are copied into Privvy's private storage. They never appear in the Android launcher or Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onLock,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Lock and stop session")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
