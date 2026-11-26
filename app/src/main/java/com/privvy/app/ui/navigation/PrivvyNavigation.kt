package com.privvy.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

enum class PrivvyDestination(
    val label: String,
    val icon: ImageVector,
    val testTag: String,
) {
    HOME("Home", Icons.Outlined.Home, "nav_home"),
    SPACES("Spaces", Icons.Outlined.FolderOpen, "nav_spaces"),
    VAULT("Vault", Icons.Outlined.Lock, "nav_vault"),
}

@Composable
fun PrivvyNavigationScaffold(
    current: PrivvyDestination,
    onNavigate: (PrivvyDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .safeDrawingPadding(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    PrivvyDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = destination == current,
                            onClick = { onNavigate(destination) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                            modifier = Modifier.testTag(destination.testTag),
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    saveableStateHolder.SaveableStateProvider("privvy_screen") {
                        content(PaddingValues(0.dp))
                    }
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                    ) {
                        PrivvyDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = destination == current,
                                onClick = { onNavigate(destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(destination.label) },
                                modifier = Modifier.testTag(destination.testTag),
                            )
                        }
                    }
                },
            ) { contentPadding ->
                saveableStateHolder.SaveableStateProvider("privvy_screen") {
                    content(contentPadding)
                }
            }
        }
    }
}
