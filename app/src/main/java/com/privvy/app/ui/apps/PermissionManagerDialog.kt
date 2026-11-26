@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.privvy.app.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.privvy.app.core.InstalledApp

data class PermissionChoice(
    val permission: String,
    val label: String,
    val granted: Boolean,
    val androidGranted: Boolean,
)

@Composable
fun PermissionManagerDialog(
    app: InstalledApp,
    choices: List<PermissionChoice>,
    onDismiss: () -> Unit,
    onSave: (List<PermissionChoice>) -> Unit,
) {
    var selected by remember(app.packageName) {
        mutableStateOf(choices.map { it.granted })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("permissions_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Permissions for ${app.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Choose what this app can use inside Privvy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(choices, key = { _, choice -> choice.permission }) { index, choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = selected[index],
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        selected = selected.toMutableList().also { it[index] = checked }
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                checked = selected[index],
                                onCheckedChange = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = choice.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (!choice.androidGranted) {
                                    Text(
                                        text = "Not currently allowed by Android",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(choices.mapIndexed { index, choice ->
                        choice.copy(granted = selected[index])
                    })
                },
                modifier = Modifier.testTag("save_permissions_${app.packageName}"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
