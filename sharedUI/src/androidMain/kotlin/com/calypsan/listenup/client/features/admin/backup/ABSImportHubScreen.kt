package com.calypsan.listenup.client.features.admin.backup
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.calypsan.listenup.client.design.components.ListenUpFab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.data.remote.ABSImportBook
import com.calypsan.listenup.client.data.remote.ABSImportResponse
import com.calypsan.listenup.client.data.remote.ABSImportSession
import com.calypsan.listenup.client.data.remote.ABSImportSummary
import com.calypsan.listenup.client.data.remote.ABSImportUser
import com.calypsan.listenup.client.data.remote.MappingFilter
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.SessionStatusFilter
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.presentation.admin.ABSImportHubUiState
import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.ImportHubTab
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import org.koin.compose.viewmodel.koinViewModel

private const val IMPORT_STATUS_ANALYZING = "analyzing"
private const val IMPORT_STATUS_ACTIVE = "active"
private const val LABEL_DELETE = "Delete"
private const val LABEL_CANCEL = "Cancel"
private const val LABEL_CANCEL_IMPORT = "Cancel Import"

// ============================================================
// Import List Screen
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ABSImportListScreen(
    viewModel: ABSImportHubViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onImportClick: (String) -> Unit,
    onLegacyImport: () -> Unit,
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteConfirmImport by remember { mutableStateOf<ABSImportSummary?>(null) }

    // Document picker for local file selection
    val documentPicker =
        rememberABSBackupPicker { result ->
            when (result) {
                is DocumentPickerResult.Success -> {
                    viewModel.createImport(result.fileSource, result.filename)
                }

                is DocumentPickerResult.Error -> {
                    // Error handled by state
                }

                DocumentPickerResult.Cancelled -> {
                    // User cancelled
                }
            }
        }

    val readyState = state as? ABSImportListUiState.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ABS Imports") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadImports() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            if (readyState != null) {
                ListenUpFab(
                    onClick = { showCreateDialog = true },
                    icon = Icons.Default.Add,
                    contentDescription = "New Import",
                )
            }
        },
    ) { paddingValues ->
        ImportListBody(
            state = state,
            onImportClick = onImportClick,
            onDeleteClick = { importId ->
                deleteConfirmImport = readyState?.imports?.find { it.id == importId }
            },
            modifier = Modifier.padding(paddingValues),
        )
    }

    deleteConfirmImport?.let { import ->
        AlertDialog(
            onDismissRequest = { deleteConfirmImport = null },
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete Import") },
            text = {
                Text("Are you sure you want to delete \"${import.name}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImport(import.id)
                        deleteConfirmImport = null
                    },
                ) {
                    Text(LABEL_DELETE, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmImport = null }) {
                    Text(LABEL_CANCEL)
                }
            },
        )
    }

    if (showCreateDialog) {
        CreateImportDialog(
            onDismiss = { showCreateDialog = false },
            onLocalFile = {
                showCreateDialog = false
                documentPicker.launch()
            },
            onLegacyWizard = {
                showCreateDialog = false
                onLegacyImport()
            },
        )
    }
}

@Composable
private fun ImportListBody(
    state: ABSImportListUiState,
    onImportClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ABSImportListUiState.Loading -> {
            FullScreenLoadingIndicator(
                modifier = modifier,
                message = "Loading imports...",
            )
        }

        is ABSImportListUiState.Error -> {
            Column(
                modifier =
                    modifier
                        .fillMaxSize()
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ABSImportListUiState.Ready -> {
            ImportListReadyContent(
                ready = state,
                onImportClick = onImportClick,
                onDeleteClick = onDeleteClick,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ImportListReadyContent(
    ready: ABSImportListUiState.Ready,
    onImportClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        ready.isCreating -> {
            FullScreenLoadingIndicator(
                modifier = modifier,
                message = "Creating import...",
            )
        }

        ready.imports.isEmpty() -> {
            EmptyImportsContent(modifier = modifier)
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ready.imports, key = { it.id }) { import ->
                    ImportSummaryCard(
                        import = import,
                        onClick = { onImportClick(import.id) },
                        onDelete = { onDeleteClick(import.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyImportsContent(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Archive,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Imports",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create a new import from an Audiobookshelf backup to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportSummaryCard(
    import: ABSImportSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress =
        if (import.totalSessions > 0) {
            import.sessionsImported.toFloat() / import.totalSessions
        } else {
            0f
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = import.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(status = import.status)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(
                    icon = Icons.Outlined.Person,
                    label = "Users",
                    value = "${import.usersMapped}/${import.totalUsers}",
                )
                StatItem(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    label = "Books",
                    value = "${import.booksMapped}/${import.totalBooks}",
                )
                StatItem(
                    icon = Icons.Outlined.History,
                    label = "Sessions",
                    value = "${import.sessionsImported}/${import.totalSessions}",
                )
            }

            val isStuck =
                import.status.lowercase() == IMPORT_STATUS_ACTIVE &&
                    import.totalUsers == 0 && import.totalBooks == 0 && import.totalSessions == 0

            if (isStuck) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Import appears stuck",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (import.status == IMPORT_STATUS_ANALYZING) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Analyzing backup…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (import.totalSessions > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = LABEL_DELETE,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor, label) =
        when (status.lowercase()) {
            IMPORT_STATUS_ACTIVE -> {
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Active",
                )
            }

            "completed" -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    "Completed",
                )
            }

            "archived" -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Archived",
                )
            }

            IMPORT_STATUS_ANALYZING -> {
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Analyzing…",
                )
            }

            "failed" -> {
                Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    "Failed",
                )
            }

            else -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    status,
                )
            }
        }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CreateImportDialog(
    onDismiss: () -> Unit,
    onLocalFile: () -> Unit,
    onLegacyWizard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("New Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose how to create a new import:")

                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onLocalFile),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
                        Column {
                            Text(
                                text = "Upload from device",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Select a backup file from your device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onLegacyWizard),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Dns, contentDescription = null)
                        Column {
                            Text(
                                text = "Import Wizard",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Step-by-step import from server file",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LABEL_CANCEL)
            }
        },
    )
}

// ============================================================
// Import Hub Detail Screen
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ABSImportHubDetailScreen(
    importId: String,
    viewModel: ABSImportHubViewModel = koinViewModel(),
    onBackClick: () -> Unit,
) {
    val state by viewModel.hubState.collectAsStateWithLifecycle()
    var showCancelConfirm by remember { mutableStateOf(false) }

    // Load import when screen opens
    androidx.compose.runtime.LaunchedEffect(importId) {
        viewModel.openImport(importId)
    }

    val topBarTitle =
        when (val s = state) {
            is ABSImportHubUiState.Ready -> s.import.name
            is ABSImportHubUiState.Loading, is ABSImportHubUiState.Error -> "Import Details"
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        ABSImportHubBody(
            state = state,
            onTabChange = viewModel::setActiveTab,
            onUsersFilterChange = viewModel::setUsersFilter,
            onActivateUserSearch = viewModel::activateUserSearch,
            onUserSearchQueryChange = viewModel::updateUserSearchQuery,
            onMapUser = viewModel::mapUser,
            onClearUserMapping = viewModel::clearUserMapping,
            onBooksFilterChange = viewModel::setBooksFilter,
            onActivateBookSearch = viewModel::activateBookSearch,
            onBookSearchQueryChange = viewModel::updateBookSearchQuery,
            onMapBook = viewModel::mapBook,
            onClearBookMapping = viewModel::clearBookMapping,
            onSessionsFilterChange = viewModel::setSessionsFilter,
            onImportSessions = viewModel::importReadySessions,
            onSkipSession = viewModel::skipSession,
            onClearImportResult = viewModel::clearImportResult,
            onCancelImport = { showCancelConfirm = true },
            modifier = Modifier.padding(paddingValues),
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(LABEL_CANCEL_IMPORT) },
            text = {
                Text("This will delete the stuck import. You can create a new one afterwards.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImport(importId)
                        showCancelConfirm = false
                        onBackClick()
                    },
                ) {
                    Text(LABEL_DELETE, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep")
                }
            },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ABSImportHubBody(
    state: ABSImportHubUiState,
    onTabChange: (ImportHubTab) -> Unit,
    onUsersFilterChange: (MappingFilter) -> Unit,
    onActivateUserSearch: (String) -> Unit,
    onUserSearchQueryChange: (String) -> Unit,
    onMapUser: (String, String) -> Unit,
    onClearUserMapping: (String) -> Unit,
    onBooksFilterChange: (MappingFilter) -> Unit,
    onActivateBookSearch: (String) -> Unit,
    onBookSearchQueryChange: (String) -> Unit,
    onMapBook: (String, String) -> Unit,
    onClearBookMapping: (String) -> Unit,
    onSessionsFilterChange: (SessionStatusFilter) -> Unit,
    onImportSessions: () -> Unit,
    onSkipSession: (String, String?) -> Unit,
    onClearImportResult: () -> Unit,
    onCancelImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ABSImportHubUiState.Loading -> {
            FullScreenLoadingIndicator(
                modifier = modifier,
                message = "Loading import...",
            )
        }

        is ABSImportHubUiState.Error -> {
            Column(
                modifier =
                    modifier
                        .fillMaxSize()
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ABSImportHubUiState.Ready -> {
            ABSImportHubReadyContent(
                state = state,
                onTabChange = onTabChange,
                onUsersFilterChange = onUsersFilterChange,
                onActivateUserSearch = onActivateUserSearch,
                onUserSearchQueryChange = onUserSearchQueryChange,
                onMapUser = onMapUser,
                onClearUserMapping = onClearUserMapping,
                onBooksFilterChange = onBooksFilterChange,
                onActivateBookSearch = onActivateBookSearch,
                onBookSearchQueryChange = onBookSearchQueryChange,
                onMapBook = onMapBook,
                onClearBookMapping = onClearBookMapping,
                onSessionsFilterChange = onSessionsFilterChange,
                onImportSessions = onImportSessions,
                onSkipSession = onSkipSession,
                onClearImportResult = onClearImportResult,
                onCancelImport = onCancelImport,
                modifier = modifier,
            )
        }
    }
}

@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ABSImportHubReadyContent(
    state: ABSImportHubUiState.Ready,
    onTabChange: (ImportHubTab) -> Unit,
    onUsersFilterChange: (MappingFilter) -> Unit,
    onActivateUserSearch: (String) -> Unit,
    onUserSearchQueryChange: (String) -> Unit,
    onMapUser: (String, String) -> Unit,
    onClearUserMapping: (String) -> Unit,
    onBooksFilterChange: (MappingFilter) -> Unit,
    onActivateBookSearch: (String) -> Unit,
    onBookSearchQueryChange: (String) -> Unit,
    onMapBook: (String, String) -> Unit,
    onClearBookMapping: (String) -> Unit,
    onSessionsFilterChange: (SessionStatusFilter) -> Unit,
    onImportSessions: () -> Unit,
    onSkipSession: (String, String?) -> Unit,
    onClearImportResult: () -> Unit,
    onCancelImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val import = state.import
    // Stuck import: status is "active" but analysis never populated any data
    val isStuckImport =
        import.status.lowercase() == IMPORT_STATUS_ACTIVE &&
            import.completedAt == null &&
            import.totalUsers == 0 && import.totalBooks == 0 && import.totalSessions == 0

    Column(modifier = modifier.fillMaxSize()) {
        // Analyzing banner
        if (import.status == IMPORT_STATUS_ANALYZING) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Analysis in progress…",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // Stuck import banner
        if (isStuckImport) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Import appears stuck",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Analysis was likely interrupted. Cancel and try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    OutlinedButton(onClick = onCancelImport) {
                        Text(LABEL_CANCEL_IMPORT)
                    }
                }
            }
        }

        // Tab row
        val tabsEnabled = import.status != IMPORT_STATUS_ANALYZING
        PrimaryTabRow(selectedTabIndex = state.activeTab.ordinal) {
            ImportHubTab.entries.forEach { tab ->
                Tab(
                    selected = state.activeTab == tab,
                    onClick = { if (tabsEnabled || tab == ImportHubTab.OVERVIEW) onTabChange(tab) },
                    enabled = tabsEnabled || tab == ImportHubTab.OVERVIEW,
                    text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        // Tab content
        AnimatedContent(
            targetState = state.activeTab,
            modifier =
                Modifier
                    .fillMaxSize()
                    .weight(1f),
            label = "hub_tab_content",
        ) { tab ->
            when (tab) {
                ImportHubTab.OVERVIEW -> {
                    OverviewTabContent(
                        import = import,
                        onNavigateToUsers = {
                            onUsersFilterChange(MappingFilter.UNMAPPED)
                            onTabChange(ImportHubTab.USERS)
                        },
                        onNavigateToBooks = {
                            onBooksFilterChange(MappingFilter.UNMAPPED)
                            onTabChange(ImportHubTab.BOOKS)
                        },
                        onNavigateToSessions = { onTabChange(ImportHubTab.SESSIONS) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                ImportHubTab.USERS -> {
                    UsersTabContent(
                        users = state.users,
                        filter = state.usersFilter,
                        isLoading = state.isLoadingUsers,
                        activeSearchAbsUserId = state.activeSearchAbsUserId,
                        searchQuery = state.userSearchQuery,
                        searchResults = state.userSearchResults,
                        isSearching = state.isSearchingUsers,
                        mappingInFlight = state.mappingInFlightUsers,
                        onFilterChange = onUsersFilterChange,
                        onActivateSearch = onActivateUserSearch,
                        onSearchQueryChange = onUserSearchQueryChange,
                        onMapUser = onMapUser,
                        onClearMapping = onClearUserMapping,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                ImportHubTab.BOOKS -> {
                    BooksTabContent(
                        books = state.books,
                        filter = state.booksFilter,
                        isLoading = state.isLoadingBooks,
                        activeSearchAbsMediaId = state.activeSearchAbsMediaId,
                        searchQuery = state.bookSearchQuery,
                        searchResults = state.bookSearchResults,
                        isSearching = state.isSearchingBooks,
                        mappingInFlight = state.mappingInFlightBooks,
                        onFilterChange = onBooksFilterChange,
                        onActivateSearch = onActivateBookSearch,
                        onSearchQueryChange = onBookSearchQueryChange,
                        onMapBook = onMapBook,
                        onClearMapping = onClearBookMapping,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                ImportHubTab.SESSIONS -> {
                    SessionsTabContent(
                        sessionsResponse = state.sessionsResponse,
                        filter = state.sessionsFilter,
                        isLoading = state.isLoadingSessions,
                        isImporting = state.isImportingSessions,
                        importResult = state.importResult,
                        onFilterChange = onSessionsFilterChange,
                        onImportSessions = onImportSessions,
                        onSkipSession = onSkipSession,
                        onClearImportResult = onClearImportResult,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// === Overview Tab ===

@Composable
private fun OverviewTabContent(
    import: ABSImportResponse,
    onNavigateToUsers: () -> Unit,
    onNavigateToBooks: () -> Unit,
    onNavigateToSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unmappedUsers = import.totalUsers - import.usersMapped
    val unmappedBooks = import.totalBooks - import.booksMapped

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        text = "Status: ${import.status.replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "Created: ${import.createdAt.substringBefore('T')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // Action cards for unmapped items (prominent when work needed)
        if (unmappedUsers > 0) {
            ActionNeededCard(
                title = "$unmappedUsers user${if (unmappedUsers != 1) "s" else ""} need mapping",
                subtitle = "${import.usersMapped} of ${import.totalUsers} mapped",
                icon = Icons.Outlined.Person,
                buttonText = "Map Users",
                onClick = onNavigateToUsers,
            )
        }

        if (unmappedBooks > 0) {
            ActionNeededCard(
                title = "$unmappedBooks book${if (unmappedBooks != 1) "s" else ""} need mapping",
                subtitle = "${import.booksMapped} of ${import.totalBooks} mapped",
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                buttonText = "Map Books",
                onClick = onNavigateToBooks,
            )
        }

        // Progress cards (clickable to navigate)
        ProgressCard(
            title = "Users",
            icon = Icons.Outlined.Person,
            mapped = import.usersMapped,
            total = import.totalUsers,
            onClick = if (unmappedUsers > 0) onNavigateToUsers else null,
        )

        ProgressCard(
            title = "Books",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            mapped = import.booksMapped,
            total = import.totalBooks,
            onClick = if (unmappedBooks > 0) onNavigateToBooks else null,
        )

        ProgressCard(
            title = "Sessions",
            icon = Icons.Outlined.History,
            mapped = import.sessionsImported,
            total = import.totalSessions,
            onClick = onNavigateToSessions,
        )
    }
}

@Composable
private fun ActionNeededCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    buttonText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onClick,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun ProgressCard(
    title: String,
    icon: ImageVector,
    mapped: Int,
    total: Int,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val progress = if (total > 0) mapped.toFloat() / total else 0f
    val isComplete = mapped == total && total > 0

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isComplete) {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (isComplete) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Complete",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$mapped / $total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// === Users Tab ===

@Suppress("LongParameterList")
@Composable
private fun UsersTabContent(
    users: List<ABSImportUser>,
    filter: MappingFilter,
    isLoading: Boolean,
    activeSearchAbsUserId: String?,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    mappingInFlight: Set<String>,
    onFilterChange: (MappingFilter) -> Unit,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMapUser: (String, String) -> Unit,
    onClearMapping: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Filter chips
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MappingFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (isLoading) {
            FullScreenLoadingIndicator(message = "Loading users...")
        } else if (users.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No users found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(users, key = { it.absUserId }) { user ->
                    HubUserMappingCard(
                        user = user,
                        isSearchActive = activeSearchAbsUserId == user.absUserId,
                        searchQuery = if (activeSearchAbsUserId == user.absUserId) searchQuery else "",
                        searchResults = if (activeSearchAbsUserId == user.absUserId) searchResults else emptyList(),
                        isSearching = activeSearchAbsUserId == user.absUserId && isSearching,
                        isMappingInFlight = user.absUserId in mappingInFlight,
                        onActivateSearch = { onActivateSearch(user.absUserId) },
                        onSearchQueryChange = onSearchQueryChange,
                        onMapUser = { listenUpId -> onMapUser(user.absUserId, listenUpId) },
                        onClearMapping = { onClearMapping(user.absUserId) },
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun HubUserMappingCard(
    user: ABSImportUser,
    isSearchActive: Boolean,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    isMappingInFlight: Boolean,
    onActivateSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMapUser: (String) -> Unit,
    onClearMapping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track if user rejected the suggestion and wants to search instead
    var showSearchOverride by remember { mutableStateOf(false) }

    // Determine if we have a confident suggestion
    val hasConfidentSuggestion =
        !user.isMapped &&
            user.confidence.lowercase() in listOf("definitive", "strong") &&
            user.suggestions.isNotEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        user.isMapped -> {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                        }

                        hasConfidentSuggestion && !showSearchOverride -> {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
                        }

                        else -> {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.absUsername,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (user.absEmail.isNotBlank()) {
                        Text(
                            text = user.absEmail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (user.isMapped) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Mapped",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            HubUserMappingCardState(
                user = user,
                isMappingInFlight = isMappingInFlight,
                searchQuery = searchQuery,
                searchResults = searchResults,
                isSearching = isSearching,
                hasConfidentSuggestion = hasConfidentSuggestion,
                showSearchOverride = showSearchOverride,
                onShowSearchOverride = { showSearchOverride = true },
                onSearchQueryChange = { query ->
                    if (!isSearchActive) onActivateSearch()
                    onSearchQueryChange(query)
                },
                onMapUser = onMapUser,
                onClearMapping = onClearMapping,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun HubUserMappingCardState(
    user: ABSImportUser,
    isMappingInFlight: Boolean,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    hasConfidentSuggestion: Boolean,
    showSearchOverride: Boolean,
    onShowSearchOverride: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMapUser: (String) -> Unit,
    onClearMapping: () -> Unit,
) {
    when {
        isMappingInFlight -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        user.isMapped -> {
            // STATE 1: Already mapped - show with clear option
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Mapped to: ${user.listenUpDisplayName ?: user.listenUpEmail ?: user.listenUpId}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClearMapping,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        hasConfidentSuggestion && !showSearchOverride -> {
            // STATE 2: Definitive/strong match - show suggestion with confirm
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text =
                                user.matchReason.ifBlank {
                                    "${user.confidence.replaceFirstChar { it.uppercase() }} match found"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ListenUpButton(
                            onClick = { onMapUser(user.suggestions.first()) },
                            text = "Confirm",
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onShowSearchOverride,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Search Instead")
                        }
                    }
                }
            }
        }

        else -> {
            // STATE 3: No confident match - show search field
            ListenUpAutocompleteField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                results = searchResults,
                onResultSelected = { result -> onMapUser(result.id) },
                onSubmit = { searchResults.firstOrNull()?.let { onMapUser(it.id) } },
                resultContent = { result ->
                    AutocompleteResultItem(
                        name = result.displayName.takeIf { it.isNotBlank() } ?: result.email,
                        subtitle = if (result.displayName.isNotBlank()) result.email else null,
                        onClick = { onMapUser(result.id) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Person,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                },
                placeholder = "Search users...",
                isLoading = isSearching,
            )
        }
    }
}

// === Books Tab ===

@Suppress("LongParameterList")
@Composable
private fun BooksTabContent(
    books: List<ABSImportBook>,
    filter: MappingFilter,
    isLoading: Boolean,
    activeSearchAbsMediaId: String?,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    mappingInFlight: Set<String>,
    onFilterChange: (MappingFilter) -> Unit,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMapBook: (String, String) -> Unit,
    onClearMapping: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Filter chips
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MappingFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (isLoading) {
            FullScreenLoadingIndicator(message = "Loading books...")
        } else if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No books found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.absMediaId }) { book ->
                    HubBookMappingCard(
                        book = book,
                        isSearchActive = activeSearchAbsMediaId == book.absMediaId,
                        searchQuery = if (activeSearchAbsMediaId == book.absMediaId) searchQuery else "",
                        searchResults = if (activeSearchAbsMediaId == book.absMediaId) searchResults else emptyList(),
                        isSearching = activeSearchAbsMediaId == book.absMediaId && isSearching,
                        isMappingInFlight = book.absMediaId in mappingInFlight,
                        onActivateSearch = { onActivateSearch(book.absMediaId) },
                        onSearchQueryChange = onSearchQueryChange,
                        onMapBook = { listenUpId -> onMapBook(book.absMediaId, listenUpId) },
                        onClearMapping = { onClearMapping(book.absMediaId) },
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList", "CognitiveComplexMethod")
@Composable
private fun HubBookMappingCard(
    book: ABSImportBook,
    isSearchActive: Boolean,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    isMappingInFlight: Boolean,
    onActivateSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMapBook: (String) -> Unit,
    onClearMapping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track if user rejected the suggestion and wants to search instead
    var showSearchOverride by remember { mutableStateOf(false) }

    // Determine if we have a confident suggestion
    val hasConfidentSuggestion =
        !book.isMapped &&
            book.confidence.lowercase() in listOf("definitive", "strong") &&
            book.suggestions.isNotEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        book.isMapped -> {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                        }

                        hasConfidentSuggestion && !showSearchOverride -> {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
                        }

                        else -> {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.absTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.absAuthor.isNotBlank()) {
                        Text(
                            text = book.absAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (book.absNarrator.isNotBlank()) {
                        Text(
                            text = "Narr. ${book.absNarrator}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (book.absDurationMs > 0) {
                        val hours = book.absDurationMs / 3_600_000
                        val minutes = book.absDurationMs % 3_600_000 / 60_000
                        Text(
                            text = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (book.isMapped) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Mapped",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            HubBookMappingCardState(
                book = book,
                isMappingInFlight = isMappingInFlight,
                searchQuery = searchQuery,
                searchResults = searchResults,
                isSearching = isSearching,
                hasConfidentSuggestion = hasConfidentSuggestion,
                showSearchOverride = showSearchOverride,
                onShowSearchOverride = { showSearchOverride = true },
                onSearchQueryChange = { query ->
                    if (!isSearchActive) onActivateSearch()
                    onSearchQueryChange(query)
                },
                onMapBook = onMapBook,
                onClearMapping = onClearMapping,
            )
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun HubBookMappingCardState(
    book: ABSImportBook,
    isMappingInFlight: Boolean,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    hasConfidentSuggestion: Boolean,
    showSearchOverride: Boolean,
    onShowSearchOverride: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMapBook: (String) -> Unit,
    onClearMapping: () -> Unit,
) {
    when {
        isMappingInFlight -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        book.isMapped -> {
            // STATE 1: Already mapped - show with clear option
            val mappedDisplay =
                book.listenUpTitle
                    ?: book.listenUpAuthor?.let { "by $it" }
                    ?: book.listenUpId
                    ?: "Unknown"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Mapped to: $mappedDisplay",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = onClearMapping,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        hasConfidentSuggestion && !showSearchOverride -> {
            // STATE 2: Definitive/strong match - show suggestion with confirm
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text =
                                book.matchReason.ifBlank {
                                    "${book.confidence.replaceFirstChar { it.uppercase() }} match found"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ListenUpButton(
                            onClick = { onMapBook(book.suggestions.first()) },
                            text = "Confirm",
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onShowSearchOverride,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Search Instead")
                        }
                    }
                }
            }
        }

        else -> {
            // STATE 3: No confident match - show search field
            ListenUpAutocompleteField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                results = searchResults,
                onResultSelected = { result -> onMapBook(result.id) },
                onSubmit = { searchResults.firstOrNull()?.let { onMapBook(it.id) } },
                resultContent = { result ->
                    val resultSubtitle =
                        buildString {
                            result.author?.let { append(it) }
                            result.narrator?.let {
                                if (isNotEmpty()) append(" · ")
                                append("Narr. $it")
                            }
                            result.duration?.let { durationMs ->
                                if (isNotEmpty()) append(" · ")
                                val h = durationMs / 3_600_000
                                val m = durationMs % 3_600_000 / 60_000
                                append(if (h > 0) "${h}h ${m}m" else "${m}m")
                            }
                        }.takeIf { it.isNotBlank() }
                    AutocompleteResultItem(
                        name = result.name,
                        subtitle = resultSubtitle,
                        onClick = { onMapBook(result.id) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                },
                placeholder = "Search books...",
                isLoading = isSearching,
            )
        }
    }
}

// === Sessions Tab ===

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SessionsTabContent(
    sessionsResponse: com.calypsan.listenup.client.data.remote.ABSSessionsResponse?,
    filter: SessionStatusFilter,
    isLoading: Boolean,
    isImporting: Boolean,
    importResult: com.calypsan.listenup.client.data.remote.ImportSessionsResult?,
    onFilterChange: (SessionStatusFilter) -> Unit,
    onImportSessions: () -> Unit,
    onSkipSession: (String, String?) -> Unit,
    onClearImportResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Stats and import button
        sessionsResponse?.let { response ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${response.readyCount}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Ready",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${response.pendingCount}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Pending",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${response.importedCount}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Imported",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    if (response.readyCount > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        ListenUpButton(
                            onClick = onImportSessions,
                            text = if (isImporting) "Importing..." else "Import ${response.readyCount} Ready Sessions",
                            enabled = !isImporting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Import result
        importResult?.let { result ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Imported ${result.imported} sessions",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = onClearImportResult) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }

        // Filter chips
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SessionStatusFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (isLoading) {
            FullScreenLoadingIndicator(message = "Loading sessions...")
        } else if (sessionsResponse?.sessions?.isEmpty() == true) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No sessions found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessionsResponse?.sessions ?: emptyList(), key = { it.absSessionId }) { session ->
                    SessionCard(
                        session = session,
                        onSkip = { onSkipSession(session.absSessionId, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ABSImportSession,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor =
        when (session.status.lowercase()) {
            "ready" -> MaterialTheme.colorScheme.primary
            "imported" -> MaterialTheme.colorScheme.tertiary
            "skipped" -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.secondary
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.startTime.substringBefore('T'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${session.duration / 60000}m listened",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = session.status.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            if (session.status.lowercase() == "ready") {
                IconButton(onClick = onSkip) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Skip",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
