@file:OptIn(ExperimentalComposeUiApi::class)

package com.privvy.app.ui.spaces

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privvy.app.core.VirtualSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun SpaceManagerScreen(
    spaces: List<VirtualSpace>,
    activeSpaceId: Int,
    message: String?,
    feedbackVersion: Int,
    cleanupInProgress: Boolean,
    onClose: () -> Unit,
    onCreateSpace: (name: String, pin: String) -> Unit,
    onRenameSpace: (id: Int, name: String) -> Unit,
    onWipeSpace: (id: Int) -> Unit,
    onDeleteSpace: (id: Int) -> Unit,
    onChangePin: (id: Int, pin: String) -> Unit,
    onCleanup: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VirtualSpace?>(null) }
    var pinTarget by remember { mutableStateOf<VirtualSpace?>(null) }
    var wipeTarget by remember { mutableStateOf<VirtualSpace?>(null) }
    var deleteTarget by remember { mutableStateOf<VirtualSpace?>(null) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(message, feedbackVersion) {
        if (!message.isNullOrBlank()) listState.scrollToItem(1)
    }

    BackHandler(onBack = onClose)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("space_manager_screen"),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Text(
                        text = "Spaces",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_space_manager"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close space manager",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!cleanupInProgress) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("create_space_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Create space")
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current) + 20.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current) + 20.dp,
                bottom = contentPadding.calculateBottomPadding() + 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${spaces.size} isolated ${if (spaces.size == 1) "workspace" else "workspaces"}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "Create separate spaces for different parts of your life. " +
                                    "Each space has its own apps, files, permissions, and PIN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            if (!message.isNullOrBlank()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }

            if (spaces.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Create your first space",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Choose a name and a 4–8 digit PIN to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(spaces, key = { it.id }) { space ->
                SpaceCard(
                    space = space,
                    isActive = space.id == activeSpaceId,
                    canDelete = spaces.size > 1,
                    onRename = { renameTarget = space },
                    onChangePin = { pinTarget = space },
                    onWipe = { wipeTarget = space },
                    onDelete = {
                        if (spaces.size > 1) {
                            deleteTarget = space
                        }
                    },
                )
            }

            item {
                CleanupCard(
                    enabled = !cleanupInProgress,
                    onClick = { showCleanupDialog = true },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateSpaceDialog(
            spaces = spaces,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, pin ->
                showCreateDialog = false
                onCreateSpace(name, pin)
            },
        )
    }

    renameTarget?.let { space ->
        RenameSpaceDialog(
            space = space,
            spaces = spaces,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                onRenameSpace(space.id, name)
            },
        )
    }

    pinTarget?.let { space ->
        ChangePinDialog(
            space = space,
            onDismiss = { pinTarget = null },
            onConfirm = { pin ->
                pinTarget = null
                onChangePin(space.id, pin)
            },
        )
    }

    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("cleanup_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Clean up all Privvy data?") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cleanup_warning"),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Warning",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "The following will be permanently deleted:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            listOf(
                                "All spaces and their PINs",
                                "Cloned apps and all guest data",
                                "Vault files",
                                "Guest permission choices",
                                "Session data and temporary files",
                            ).forEach { item ->
                                Text(
                                    text = "• $item",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Guest apps will be stopped. Privvy will return to PIN setup " +
                            "with a fresh Personal space.",
                    )
                    Text(
                        "Apps installed on your phone and the Privvy installation " +
                            "will not be removed.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "This cannot be undone.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupDialog = false
                        onCleanup()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_cleanup"),
                ) {
                    Text("Delete everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    wipeTarget?.let { space ->
        DestructiveSpaceDialog(
            space = space,
            action = SpaceAction.WIPE,
            onDismiss = { wipeTarget = null },
            onConfirm = {
                wipeTarget = null
                onWipeSpace(space.id)
            },
        )
    }

    deleteTarget?.takeIf { spaces.size > 1 }?.let { space ->
        DestructiveSpaceDialog(
            space = space,
            action = SpaceAction.DELETE,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDeleteSpace(space.id)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpaceCard(
    space: VirtualSpace,
    isActive: Boolean,
    canDelete: Boolean,
    onRename: () -> Unit,
    onChangePin: () -> Unit,
    onWipe: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("space_card_${space.id}"),
        border = if (isActive) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Active space",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    } else {
                        Text(
                            text = space.name.trim().firstOrNull()?.uppercase().orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = space.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (space.createdAt > 0L) {
                        Text(
                            text = "Created ${formatSpaceDate(space.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isActive) {
                    StatusPill(
                        text = "Active",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                StatusPill(
                    text = if (space.pinHashHex != null) "PIN protected" else "No PIN",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (space.isDecoy) {
                    StatusPill(
                        text = "Decoy",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRename,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_space_${space.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Rename")
                }
                OutlinedButton(
                    onClick = onChangePin,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_pin_${space.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Change PIN")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onWipe,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("wipe_space_${space.id}"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Wipe data")
                }
                TextButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("delete_space_${space.id}"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            }
            if (!canDelete) {
                Text(
                    text = "At least one space must remain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CreateSpaceDialog(
    spaces: List<VirtualSpace>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val cleanName = name.trim()
        val nameError = validateSpaceName(cleanName, spaces, excludedId = null)
        when {
            nameError != null -> error = nameError
            !isValidPin(pin) -> error = "PIN must be 4–8 digits"
            pin != confirmPin -> error = "PINs do not match"
            else -> onConfirm(cleanName, pin)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("create_space_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Create space") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Give this space a name and protect it with a PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Space name") },
                    supportingText = {
                        DialogFieldSupport(
                            error = error?.takeUnless { it.contains("PIN") },
                            hint = "1–40 characters",
                        )
                    },
                    isError = error != null && error?.contains("PIN") != true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_space_name"),
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        error = null
                    },
                    label = { Text("PIN") },
                    supportingText = {
                        DialogFieldSupport(
                            error = error?.takeIf { it.contains("PIN") && !it.contains("match") },
                            hint = "4–8 digits",
                        )
                    },
                    isError = error?.contains("PIN") == true && error?.contains("match") != true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_space_pin"),
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        confirmPin = it
                        error = null
                    },
                    label = { Text("Confirm PIN") },
                    supportingText = {
                        DialogFieldSupport(
                            error = error?.takeIf { it.contains("match") },
                        )
                    },
                    isError = error?.contains("match") == true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_space_pin_confirm"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = name.isNotBlank() && pin.isNotEmpty() && confirmPin.isNotEmpty(),
                modifier = Modifier.testTag("confirm_create_space"),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RenameSpaceDialog(
    space: VirtualSpace,
    spaces: List<VirtualSpace>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(space.id) { mutableStateOf(space.name) }
    var error by remember(space.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("rename_space_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Rename space") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text("Space name") },
                supportingText = {
                    DialogFieldSupport(error = error, hint = "1–40 characters")
                },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val cleanName = name.trim()
                    val validationError = validateSpaceName(cleanName, spaces, space.id)
                    if (validationError == null) {
                        onConfirm(cleanName)
                    } else {
                        error = validationError
                    }
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_space_name"),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    val validationError = validateSpaceName(cleanName, spaces, space.id)
                    if (validationError == null) {
                        onConfirm(cleanName)
                    } else {
                        error = validationError
                    }
                },
                modifier = Modifier.testTag("confirm_rename_space"),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ChangePinDialog(
    space: VirtualSpace,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember(space.id) { mutableStateOf("") }
    var confirmPin by remember(space.id) { mutableStateOf("") }
    var error by remember(space.id) { mutableStateOf<String?>(null) }

    fun submit() {
        when {
            !isValidPin(pin) -> error = "PIN must be 4–8 digits"
            pin != confirmPin -> error = "PINs do not match"
            else -> onConfirm(pin)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("change_pin_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Change PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Choose a new 4–8 digit PIN for ${space.name}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        error = null
                    },
                    label = { Text("New PIN") },
                    supportingText = {
                        DialogFieldSupport(
                            error = error?.takeIf { !it.contains("match") },
                            hint = "4–8 digits",
                        )
                    },
                    isError = error?.contains("match") == false,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_space_pin"),
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        confirmPin = it
                        error = null
                    },
                    label = { Text("Confirm new PIN") },
                    supportingText = {
                        DialogFieldSupport(error = error?.takeIf { it.contains("match") })
                    },
                    isError = error?.contains("match") == true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_space_pin_confirm"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = pin.isNotEmpty() && confirmPin.isNotEmpty(),
                modifier = Modifier.testTag("confirm_change_pin"),
            ) {
                Text("Change PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Destructive entry point for the confirmed global reset. */
@Composable
private fun CleanupCard(enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleanup_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Start fresh",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Remove all Privvy spaces, cloned apps, vault files, and " +
                    "temporary data. Your phone's installed apps stay untouched.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cleanup_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text("Clean up Privvy")
            }
        }
    }
}

@Composable
private fun DestructiveSpaceDialog(
    space: VirtualSpace,
    action: SpaceAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isWipe = action == SpaceAction.WIPE
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag(
                if (isWipe) "wipe_space_dialog" else "delete_space_dialog",
            ),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(if (isWipe) "Wipe ${space.name}?" else "Delete ${space.name}?")
        },
        text = {
            Text(
                if (isWipe) {
                    "All guest apps and their data in this space will be removed. " +
                        "The space, its name, and its PIN will remain. This cannot be undone."
                } else {
                    "This permanently deletes this space and everything inside it. " +
                        "This cannot be undone."
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(
                    if (isWipe) "confirm_wipe_space" else "confirm_delete_space",
                ),
            ) {
                Text(if (isWipe) "Wipe" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DialogFieldSupport(error: String?, hint: String? = null) {
    Text(
        text = error ?: hint.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private enum class SpaceAction { WIPE, DELETE }

private fun validateSpaceName(
    name: String,
    spaces: List<VirtualSpace>,
    excludedId: Int?,
): String? = when {
    name.isEmpty() -> "Enter a space name"
    name.length > 40 -> "Space name must be 40 characters or fewer"
    spaces.any { it.id != excludedId && it.name.equals(name, ignoreCase = true) } ->
        "A space with this name already exists"
    else -> null
}

private fun isValidPin(pin: String): Boolean =
    pin.length in 4..8 && pin.all { it in '0'..'9' }

private fun formatSpaceDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
