@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.features.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.design.theme.LocalDarkTheme
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Library setup screen for initial library configuration.
 *
 * Allows users to browse the server filesystem and select a folder
 * for their audiobook library. Shows a folder browser with navigation
 * and a bottom bar for confirming the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibrarySetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle setup completion
    LaunchedEffect(state.setupComplete) {
        if (state.setupComplete) {
            onSetupComplete()
        }
    }

    // Handle errors
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Library Setup") },
                navigationIcon = {
                    if (!state.isRoot) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Go back",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when {
                state.isCheckingStatus -> {
                    FullScreenLoadingIndicator()
                }

                !state.needsSetup -> {
                    // Library already set up, wait for navigation
                    FullScreenLoadingIndicator()
                }

                else -> {
                    LibrarySetupContent(
                        state = state,
                        onDirectoryClick = viewModel::loadDirectory,
                        onTogglePath = viewModel::togglePath,
                        onSelectCurrentFolder = {
                            viewModel.selectPath(state.currentPath)
                        },
                        selectedPaths = state.selectedPaths,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Bottom bar for confirming selection
            AnimatedVisibility(
                visible = state.selectedPaths.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                SelectionBottomBar(
                    selectedCount = state.selectedPaths.size,
                    isCreating = state.isCreatingLibrary,
                    onCreateLibrary = viewModel::createLibrary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LibrarySetupContent(
    state: LibrarySetupUiState,
    onDirectoryClick: (String) -> Unit,
    onTogglePath: (String) -> Unit,
    onSelectCurrentFolder: () -> Unit,
    selectedPaths: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Welcome header (only at root with nothing selected)
        if (state.isRoot && state.selectedPaths.isEmpty()) {
            WelcomeHeader(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
            )
        }

        // Path breadcrumb
        PathBreadcrumb(
            path = state.currentPath,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.isLoadingDirectories) {
            FullScreenLoadingIndicator()
        } else if (state.directories.isEmpty()) {
            // Empty directory message
            EmptyDirectoryMessage(
                currentPath = state.currentPath,
                onSelectCurrentFolder = onSelectCurrentFolder,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
        } else {
            // Directory list
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                items(
                    items = state.directories,
                    key = { it.path },
                ) { directory ->
                    DirectoryListItem(
                        directory = directory,
                        isSelected = directory.path in selectedPaths,
                        onNavigate = { onDirectoryClick(directory.path) },
                        onSelect = { onTogglePath(directory.path) },
                    )
                }

                // Extra padding at bottom for bottom bar
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeader(modifier: Modifier = Modifier) {
    val isDarkTheme = LocalDarkTheme.current
    val logoRes =
        if (isDarkTheme) {
            R.drawable.listenup_logo_white
        } else {
            R.drawable.listenup_logo_black
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(logoRes),
            contentDescription = "ListenUp Logo",
            modifier = Modifier.size(120.dp),
        )
        Text(
            text = "Welcome to ListenUp",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Select one or more folders where your audiobooks are stored",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PathBreadcrumb(
    path: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DirectoryListItem(
    directory: DirectoryEntryResponse,
    isSelected: Boolean,
    onNavigate: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        }

    ListItem(
        headlineContent = {
            Text(
                text = directory.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Radio button for selection
                IconButton(onClick = onSelect) {
                    Icon(
                        imageVector =
                            if (isSelected) {
                                Icons.Outlined.CheckBox
                            } else {
                                Icons.Outlined.CheckBoxOutlineBlank
                            },
                        contentDescription =
                            if (isSelected) {
                                "Selected"
                            } else {
                                "Select this folder"
                            },
                        tint =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                // Chevron to navigate into folder
                IconButton(onClick = onNavigate) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = "Open folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = backgroundColor),
        modifier = modifier.clickable(onClick = onNavigate),
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun EmptyDirectoryMessage(
    currentPath: String,
    onSelectCurrentFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No subdirectories",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This folder has no subdirectories. You can select it as your library location.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        ListenUpButton(
            text = "Select This Folder",
            onClick = onSelectCurrentFolder,
            modifier = Modifier.width(200.dp),
        )
    }
}

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    isCreating: Boolean,
    onCreateLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Selected count display
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (selectedCount == 1) "1 folder selected" else "$selectedCount folders selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Create library button
            ListenUpButton(
                text = "Create Library",
                onClick = onCreateLibrary,
                isLoading = isCreating,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
