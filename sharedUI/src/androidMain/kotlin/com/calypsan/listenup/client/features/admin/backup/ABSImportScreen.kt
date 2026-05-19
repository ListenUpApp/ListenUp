package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.data.remote.model.ABSBookMatch
import com.calypsan.listenup.client.data.remote.model.ABSBookSuggestion
import com.calypsan.listenup.client.data.remote.model.ABSUserMatch
import com.calypsan.listenup.client.data.remote.model.ABSUserSuggestion
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.presentation.admin.ABSImportStep
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.admin.ABSImportViewModel
import com.calypsan.listenup.client.presentation.admin.ABSSourceType
import com.calypsan.listenup.client.presentation.admin.BookMappingTab
import com.calypsan.listenup.client.presentation.admin.SelectedBookDisplay
import com.calypsan.listenup.client.presentation.admin.SelectedUserDisplay
import com.calypsan.listenup.client.presentation.admin.UserMappingTab
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ABSImportScreen(
    viewModel: ABSImportViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onComplete: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    // Document picker for local file selection
    val documentPicker =
        rememberABSBackupPicker { result ->
            when (result) {
                is DocumentPickerResult.Success -> {
                    // Pass the streaming file source (not byte array) to avoid OOM
                    viewModel.setLocalFile(result.fileSource, result.filename, result.size)
                }

                is DocumentPickerResult.Error -> {
                    // Error is shown via state
                }

                DocumentPickerResult.Cancelled -> {
                    // User cancelled, do nothing
                }
            }
        }

    when (val state = uiState) {
        ABSImportUiState.Loading -> {
            FullScreenLoadingIndicator(message = "Loading\u2026")
        }

        is ABSImportUiState.Error -> {
            ABSImportErrorScaffold(message = state.message, onBackClick = onBackClick)
        }

        is ABSImportUiState.Ready -> {
            ABSImportReadyContent(
                state = state,
                viewModel = viewModel,
                documentPicker = documentPicker,
                onBackClick = onBackClick,
                onComplete = onComplete,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ABSImportErrorScaffold(
    message: String,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from Audiobookshelf") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ErrorCard(text = message)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
private fun ABSImportReadyContent(
    state: ABSImportUiState.Ready,
    viewModel: ABSImportViewModel,
    documentPicker: com.calypsan.listenup.client.util.DocumentPickerState,
    onBackClick: () -> Unit,
    onComplete: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(getStepTitle(state.step)) },
                navigationIcon = {
                    if (canNavigateBack(state.step)) {
                        IconButton(onClick = {
                            if (state.step == ABSImportStep.SOURCE_SELECTION) {
                                onBackClick()
                            } else {
                                viewModel.previousStep()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (state.step) {
            ABSImportStep.SOURCE_SELECTION -> {
                SourceSelectionContent(
                    state = state,
                    onSelectLocal = {
                        viewModel.selectSourceType(ABSSourceType.LOCAL)
                        documentPicker.launch()
                    },
                    onSelectRemote = { viewModel.selectSourceType(ABSSourceType.REMOTE) },
                    onPickDifferentFile = { documentPicker.launch() },
                    onClearFile = { viewModel.clearLocalFile() },
                    onProceed = { viewModel.uploadAndAnalyze() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.FILE_BROWSER -> {
                FileBrowserContent(
                    state = state,
                    onDirectoryClick = { viewModel.loadDirectory(it) },
                    onFileSelect = { viewModel.setFullRemotePath(it) },
                    onNavigateUp = { viewModel.navigateUp() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.UPLOADING -> {
                UploadingContent(
                    state = state,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.ANALYZING -> {
                AnalyzingContent(
                    phase = state.analyzePhase,
                    current = state.analyzeCurrent,
                    total = state.analyzeTotal,
                    totalBooks = state.totalBooks,
                    totalUsers = state.totalUsers,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.USER_MAPPING -> {
                UserMappingContent(
                    state = state,
                    onTabChange = viewModel::setUserMappingTab,
                    onActivateSearch = viewModel::activateUserSearch,
                    onSearchQueryChange = viewModel::updateUserSearchQuery,
                    onSelectUser = viewModel::selectUser,
                    onClearMapping = viewModel::clearUserMapping,
                    onNext = viewModel::nextStep,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.BOOK_MAPPING -> {
                BookMappingContent(
                    state = state,
                    onTabChange = viewModel::setBookMappingTab,
                    onActivateSearch = viewModel::activateBookSearch,
                    onSearchQueryChange = viewModel::updateBookSearchQuery,
                    onSelectBook = viewModel::selectBook,
                    onClearMapping = viewModel::clearBookMapping,
                    onNext = viewModel::nextStep,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.IMPORT_OPTIONS -> {
                ImportOptionsContent(
                    state = state,
                    onImportSessionsChange = viewModel::setImportSessions,
                    onImportProgressChange = viewModel::setImportProgress,
                    onRebuildProgressChange = viewModel::setRebuildProgress,
                    onImport = viewModel::nextStep,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.IMPORTING -> {
                ImportingContent(
                    modifier = Modifier.padding(paddingValues),
                )
            }

            ABSImportStep.RESULTS -> {
                ResultsContent(
                    state = state,
                    onDone = onComplete,
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}

private fun canNavigateBack(step: ABSImportStep): Boolean =
    when (step) {
        ABSImportStep.UPLOADING,
        ABSImportStep.ANALYZING,
        ABSImportStep.IMPORTING,
        ABSImportStep.RESULTS,
        -> false

        else -> true
    }

private fun getStepTitle(step: ABSImportStep): String =
    when (step) {
        ABSImportStep.SOURCE_SELECTION -> "Import from Audiobookshelf"
        ABSImportStep.FILE_BROWSER -> "Select Backup File"
        ABSImportStep.UPLOADING -> "Uploading..."
        ABSImportStep.ANALYZING -> "Analyzing..."
        ABSImportStep.USER_MAPPING -> "Map Users"
        ABSImportStep.BOOK_MAPPING -> "Map Books"
        ABSImportStep.IMPORT_OPTIONS -> "Import Options"
        ABSImportStep.IMPORTING -> "Importing..."
        ABSImportStep.RESULTS -> "Import Complete"
    }

// === Source Selection ===

@Composable
private fun SourceSelectionContent(
    state: ABSImportUiState.Ready,
    onSelectLocal: () -> Unit,
    onSelectRemote: () -> Unit,
    onPickDifferentFile: () -> Unit,
    onClearFile: () -> Unit,
    onProceed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Import listening history from an Audiobookshelf backup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Instructions card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How to export from Audiobookshelf",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text =
                        "1. In Audiobookshelf, go to Settings > Backups\n" +
                            "2. Create a new backup or download an existing one\n" +
                            "3. Choose how to import below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        // Local file selected - show it
        state.selectedLocalFile?.let { file ->
            SelectedFileCard(
                filename = file.filename,
                size = formatFileSize(file.size),
                onPickDifferent = onPickDifferentFile,
                onClear = onClearFile,
            )
        }

        // Source type options (only show if no file selected yet)
        if (state.selectedLocalFile == null) {
            Text(
                text = "Select backup source",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            SourceOptionCard(
                icon = Icons.Outlined.PhoneAndroid,
                title = "From this device",
                description = "Select a backup file downloaded to your phone or tablet",
                onClick = onSelectLocal,
            )

            SourceOptionCard(
                icon = Icons.Outlined.Dns,
                title = "From server",
                description = "Browse and select a backup file on the ListenUp server",
                onClick = onSelectRemote,
            )
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Show proceed button when local file is selected
        if (state.selectedLocalFile != null) {
            ListenUpButton(
                onClick = onProceed,
                text = "Upload & Analyze",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SourceOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelectedFileCard(
    filename: String,
    size: String,
    onPickDifferent: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPickDifferent,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Pick Different")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

@Suppress("MagicNumber")
private fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }

// === File Browser ===

@Composable
private fun FileBrowserContent(
    state: ABSImportUiState.Ready,
    onDirectoryClick: (String) -> Unit,
    onFileSelect: (String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filename by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        // Current path breadcrumb
        PathBreadcrumb(
            path = state.currentPath,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.isLoadingDirectories) {
            FullScreenLoadingIndicator(
                modifier = Modifier.weight(1f),
                message = "Loading...",
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                // Navigate up option if not at root
                if (!state.isRoot) {
                    item {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = "..",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            supportingContent = {
                                Text("Parent directory")
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable { onNavigateUp() },
                        )
                        HorizontalDivider()
                    }
                }

                items(
                    items = state.directories,
                    key = { it.path },
                ) { entry ->
                    DirectoryBrowserItem(
                        entry = entry,
                        onNavigate = { onDirectoryClick(entry.path) },
                    )
                    HorizontalDivider()
                }

                if (state.directories.isEmpty()) {
                    item {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Empty directory",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(
                text = error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Filename input and select button
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListenUpTextField(
                value = filename,
                onValueChange = { filename = it },
                label = "Backup filename",
                placeholder = "e.g., backup-2024-01-15.audiobookshelf",
            )

            ListenUpButton(
                onClick = {
                    val currentPath = state.currentPath
                    val fullPath =
                        if (currentPath.endsWith("/")) {
                            "$currentPath$filename"
                        } else {
                            "$currentPath/$filename"
                        }
                    onFileSelect(fullPath)
                },
                text = "Select Backup",
                enabled = filename.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
private fun DirectoryBrowserItem(
    entry: DirectoryEntryResponse,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier.clickable { onNavigate() },
    )
}

// === Uploading ===

@Composable
private fun UploadingContent(
    state: ABSImportUiState.Ready,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        ListenUpLoadingIndicator()
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Uploading Backup...",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        state.selectedLocalFile?.let { file ->
            Text(
                text = file.filename,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// === Analyzing ===

@Composable
private fun AnalyzingContent(
    phase: String,
    current: Int,
    total: Int,
    totalBooks: Int,
    totalUsers: Int,
    modifier: Modifier = Modifier,
) {
    val phaseText =
        when (phase) {
            "parsing" -> {
                "Parsing backup..."
            }

            "matching_users" -> {
                if (totalUsers > 0) "Matching $totalUsers users\u2026" else "Matching users..."
            }

            "matching_books" -> {
                if (totalBooks > 0) "Matching $totalBooks books\u2026" else "Matching books..."
            }

            "matching_sessions" -> {
                "Analyzing sessions..."
            }

            "matching_progress" -> {
                "Analyzing progress..."
            }

            "done" -> {
                "Finishing up..."
            }

            else -> {
                if (totalBooks > 0) "Analyzing $totalBooks books\u2026" else "Analyzing..."
            }
        }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = phaseText,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (totalBooks > 0 || totalUsers > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    listOfNotNull(
                        if (totalBooks > 0) "$totalBooks books" else null,
                        if (totalUsers > 0) "$totalUsers users" else null,
                    ).joinToString(" \u00b7 "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (total > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$current / $total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            @Suppress("MagicNumber")
            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(0.6f),
            )
        }
    }
}

// === User Mapping ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserMappingContent(
    state: ABSImportUiState.Ready,
    onTabChange: (UserMappingTab) -> Unit,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectUser: (String, String, String, String?) -> Unit,
    onClearMapping: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Users are "auto-matched" if the server found a listenupId match
    val autoMatchedUsers = state.userMatches.filter { it.listenupId != null }
    val needsReviewUsers = state.userMatches.filter { it.listenupId == null }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Text(
            text = "Match Audiobookshelf users to ListenUp users.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // M3 Expressive: SegmentedButton for tabs
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.userMappingTab == UserMappingTab.NEEDS_REVIEW,
                onClick = { onTabChange(UserMappingTab.NEEDS_REVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text("Needs Review (${needsReviewUsers.size})")
            }
            SegmentedButton(
                selected = state.userMappingTab == UserMappingTab.AUTO_MATCHED,
                onClick = { onTabChange(UserMappingTab.AUTO_MATCHED) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text("Matched (${autoMatchedUsers.size})")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tab content with animation
        AnimatedContent(
            targetState = state.userMappingTab,
            modifier = Modifier.weight(1f),
            label = "user_mapping_tab",
        ) { tab ->
            when (tab) {
                UserMappingTab.NEEDS_REVIEW -> {
                    UserNeedsReviewTabContent(
                        users = needsReviewUsers,
                        selectedUserDisplays = state.selectedUserDisplays,
                        activeSearchAbsUserId = state.activeSearchAbsUserId,
                        searchQuery = state.userSearchQuery,
                        searchResults = state.userSearchResults,
                        isSearching = state.isSearchingUsers,
                        onActivateSearch = onActivateSearch,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectUser = onSelectUser,
                        onClearMapping = onClearMapping,
                        loadingUserItemId = state.loadingUserItemId,
                    )
                }

                UserMappingTab.AUTO_MATCHED -> {
                    UserAutoMatchedTabContent(
                        users = autoMatchedUsers,
                        selectedUserDisplays = state.selectedUserDisplays,
                        activeSearchAbsUserId = state.activeSearchAbsUserId,
                        searchQuery = state.userSearchQuery,
                        searchResults = state.userSearchResults,
                        isSearching = state.isSearchingUsers,
                        onActivateSearch = onActivateSearch,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectUser = onSelectUser,
                        onClearMapping = onClearMapping,
                        loadingUserItemId = state.loadingUserItemId,
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress indicator
        val mappedCount = state.userMappings.size
        val totalCount = state.userMatches.size
        ListenUpButton(
            onClick = onNext,
            text = "Continue ($mappedCount/$totalCount mapped)",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun UserNeedsReviewTabContent(
    users: List<ABSUserMatch>,
    selectedUserDisplays: Map<String, SelectedUserDisplay>,
    activeSearchAbsUserId: String?,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectUser: (String, String, String, String?) -> Unit,
    onClearMapping: (String) -> Unit,
    loadingUserItemId: String? = null,
) {
    if (users.isEmpty()) {
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
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "All users matched automatically!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(users, key = { it.absUserId }) { userMatch ->
                UserMappingCard(
                    userMatch = userMatch,
                    selectedDisplay = selectedUserDisplays[userMatch.absUserId],
                    isSearchActive = activeSearchAbsUserId == userMatch.absUserId,
                    searchQuery = if (activeSearchAbsUserId == userMatch.absUserId) searchQuery else "",
                    searchResults = if (activeSearchAbsUserId == userMatch.absUserId) searchResults else emptyList(),
                    isSearching = activeSearchAbsUserId == userMatch.absUserId && isSearching,
                    onActivateSearch = { onActivateSearch(userMatch.absUserId) },
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectUser = { id, email, displayName ->
                        onSelectUser(userMatch.absUserId, id, email, displayName)
                    },
                    onClearMapping = { onClearMapping(userMatch.absUserId) },
                    loadingUserItemId = loadingUserItemId,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun UserAutoMatchedTabContent(
    users: List<ABSUserMatch>,
    selectedUserDisplays: Map<String, SelectedUserDisplay>,
    activeSearchAbsUserId: String?,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectUser: (String, String, String, String?) -> Unit,
    onClearMapping: (String) -> Unit,
    loadingUserItemId: String? = null,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(users, key = { it.absUserId }) { userMatch ->
            UserMappingCard(
                userMatch = userMatch,
                selectedDisplay = selectedUserDisplays[userMatch.absUserId],
                isSearchActive = activeSearchAbsUserId == userMatch.absUserId,
                searchQuery = if (activeSearchAbsUserId == userMatch.absUserId) searchQuery else "",
                searchResults = if (activeSearchAbsUserId == userMatch.absUserId) searchResults else emptyList(),
                isSearching = activeSearchAbsUserId == userMatch.absUserId && isSearching,
                onActivateSearch = { onActivateSearch(userMatch.absUserId) },
                onSearchQueryChange = onSearchQueryChange,
                onSelectUser = { id, email, displayName ->
                    onSelectUser(userMatch.absUserId, id, email, displayName)
                },
                onClearMapping = { onClearMapping(userMatch.absUserId) },
                loadingUserItemId = loadingUserItemId,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun UserMappingCard(
    userMatch: ABSUserMatch,
    selectedDisplay: SelectedUserDisplay?,
    isSearchActive: Boolean,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    onActivateSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectUser: (String, String, String?) -> Unit,
    onClearMapping: () -> Unit,
    modifier: Modifier = Modifier,
    loadingUserItemId: String? = null,
) {
    val hasSelection = selectedDisplay != null

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (hasSelection) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ABS user info header
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
                        text = userMatch.absUsername,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    userMatch.absEmail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Status indicator
                if (hasSelection) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Matched",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    ConfidenceBadge(confidence = userMatch.confidence)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Selection area with animation
            AnimatedContent(
                targetState = hasSelection,
                label = "user_selection_state",
            ) { showSelected ->
                if (showSelected && selectedDisplay != null) {
                    // Show selected user with clear option
                    SelectedUserChip(
                        display = selectedDisplay,
                        onClear = onClearMapping,
                    )
                } else {
                    // Show search field
                    UserSearchField(
                        suggestions = userMatch.suggestions,
                        searchQuery = searchQuery,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        isActive = isSearchActive,
                        onActivate = onActivateSearch,
                        onQueryChange = onSearchQueryChange,
                        onSelectUser = onSelectUser,
                        loadingItemId = loadingUserItemId,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedUserChip(
    display: SelectedUserDisplay,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = display.displayName ?: display.email,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (display.displayName != null) {
                    Text(
                        text = display.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Change selection",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun UserSearchField(
    suggestions: List<ABSUserSuggestion>,
    searchQuery: String,
    searchResults: List<UserSearchResult>,
    isSearching: Boolean,
    isActive: Boolean,
    onActivate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectUser: (String, String, String?) -> Unit,
    modifier: Modifier = Modifier,
    loadingItemId: String? = null,
) {
    // Combine suggestions with search results
    // Show suggestions when query is empty, search results when typing
    val displayResults: List<UserSearchItem> =
        if (searchQuery.isEmpty() && suggestions.isNotEmpty()) {
            suggestions.map { suggestion ->
                UserSearchItem(
                    id = suggestion.userId,
                    email = suggestion.email ?: "",
                    displayName = suggestion.displayName,
                    isSuggestion = true,
                )
            }
        } else {
            searchResults.map { result ->
                UserSearchItem(
                    id = result.id,
                    email = result.email,
                    displayName = result.displayName.takeIf { it.isNotBlank() },
                    isSuggestion = false,
                )
            }
        }

    Column(modifier = modifier) {
        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = { query ->
                if (!isActive) onActivate()
                onQueryChange(query)
            },
            results = displayResults,
            onResultSelected = { item ->
                onSelectUser(item.id, item.email, item.displayName)
            },
            onSubmit = { query ->
                // Select top result if available
                displayResults.firstOrNull()?.let { item ->
                    onSelectUser(item.id, item.email, item.displayName)
                }
            },
            resultContent = { item ->
                UserSearchResultItem(
                    item = item,
                    onClick = { onSelectUser(item.id, item.email, item.displayName) },
                    isLoading = loadingItemId == item.id,
                )
            },
            placeholder =
                if (suggestions.isNotEmpty()) {
                    "Tap to see suggestions or search..."
                } else {
                    "Search users by name or email..."
                },
            isLoading = isSearching,
        )

        // Show hint when field is empty but has suggestions
        AnimatedVisibility(
            visible = searchQuery.isEmpty() && suggestions.isNotEmpty() && !isActive,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = "${suggestions.size} suggestion${if (suggestions.size != 1) "s" else ""} available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/**
 * Unified data class for user search results and suggestions.
 */
private data class UserSearchItem(
    val id: String,
    val email: String,
    val displayName: String?,
    val isSuggestion: Boolean,
)

@Composable
private fun UserSearchResultItem(
    item: UserSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    AutocompleteResultItem(
        name = item.displayName ?: item.email,
        subtitle = item.displayName?.let { item.email },
        onClick = onClick,
        modifier = modifier,
        isLoading = isLoading,
        leadingIcon = {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint =
                    if (item.isSuggestion) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

// === Book Mapping ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookMappingContent(
    state: ABSImportUiState.Ready,
    onTabChange: (BookMappingTab) -> Unit,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectBook: (String, String, String, String?, Long?) -> Unit,
    onClearMapping: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Books are "auto-matched" if the server found a listenupId match
    val autoMatchedBooks = state.bookMatches.filter { it.listenupId != null }
    val needsReviewBooks = state.bookMatches.filter { it.listenupId == null }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Text(
            text = "Match Audiobookshelf books to your ListenUp library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // M3 Expressive: SegmentedButton for tabs
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.bookMappingTab == BookMappingTab.NEEDS_REVIEW,
                onClick = { onTabChange(BookMappingTab.NEEDS_REVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text("Needs Review (${needsReviewBooks.size})")
            }
            SegmentedButton(
                selected = state.bookMappingTab == BookMappingTab.AUTO_MATCHED,
                onClick = { onTabChange(BookMappingTab.AUTO_MATCHED) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text("Matched (${autoMatchedBooks.size})")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tab content with animation
        AnimatedContent(
            targetState = state.bookMappingTab,
            modifier = Modifier.weight(1f),
            label = "book_mapping_tab",
        ) { tab ->
            when (tab) {
                BookMappingTab.NEEDS_REVIEW -> {
                    NeedsReviewTabContent(
                        books = needsReviewBooks,
                        selectedBookDisplays = state.selectedBookDisplays,
                        activeSearchAbsItemId = state.activeSearchAbsItemId,
                        searchQuery = state.bookSearchQuery,
                        searchResults = state.bookSearchResults,
                        isSearching = state.isSearchingBooks,
                        onActivateSearch = onActivateSearch,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectBook = onSelectBook,
                        onClearMapping = onClearMapping,
                        loadingBookItemId = state.loadingBookItemId,
                    )
                }

                BookMappingTab.AUTO_MATCHED -> {
                    AutoMatchedTabContent(
                        books = autoMatchedBooks,
                        selectedBookDisplays = state.selectedBookDisplays,
                        activeSearchAbsItemId = state.activeSearchAbsItemId,
                        searchQuery = state.bookSearchQuery,
                        searchResults = state.bookSearchResults,
                        isSearching = state.isSearchingBooks,
                        onActivateSearch = onActivateSearch,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectBook = onSelectBook,
                        onClearMapping = onClearMapping,
                        loadingBookItemId = state.loadingBookItemId,
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress indicator
        val mappedCount = state.bookMappings.size
        val totalCount = state.bookMatches.size
        ListenUpButton(
            onClick = onNext,
            text = "Continue ($mappedCount/$totalCount mapped)",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun NeedsReviewTabContent(
    books: List<ABSBookMatch>,
    selectedBookDisplays: Map<String, SelectedBookDisplay>,
    activeSearchAbsItemId: String?,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectBook: (String, String, String, String?, Long?) -> Unit,
    onClearMapping: (String) -> Unit,
    loadingBookItemId: String? = null,
) {
    if (books.isEmpty()) {
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
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "All books matched automatically!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(books, key = { it.absItemId }) { bookMatch ->
                BookMappingCard(
                    bookMatch = bookMatch,
                    selectedDisplay = selectedBookDisplays[bookMatch.absItemId],
                    isSearchActive = activeSearchAbsItemId == bookMatch.absItemId,
                    searchQuery = if (activeSearchAbsItemId == bookMatch.absItemId) searchQuery else "",
                    searchResults = if (activeSearchAbsItemId == bookMatch.absItemId) searchResults else emptyList(),
                    isSearching = activeSearchAbsItemId == bookMatch.absItemId && isSearching,
                    onActivateSearch = { onActivateSearch(bookMatch.absItemId) },
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectBook = { id, title, author, duration ->
                        onSelectBook(bookMatch.absItemId, id, title, author, duration)
                    },
                    onClearMapping = { onClearMapping(bookMatch.absItemId) },
                    loadingBookItemId = loadingBookItemId,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun AutoMatchedTabContent(
    books: List<ABSBookMatch>,
    selectedBookDisplays: Map<String, SelectedBookDisplay>,
    activeSearchAbsItemId: String?,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    onActivateSearch: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectBook: (String, String, String, String?, Long?) -> Unit,
    onClearMapping: (String) -> Unit,
    loadingBookItemId: String? = null,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.absItemId }) { bookMatch ->
            BookMappingCard(
                bookMatch = bookMatch,
                selectedDisplay = selectedBookDisplays[bookMatch.absItemId],
                isSearchActive = activeSearchAbsItemId == bookMatch.absItemId,
                searchQuery = if (activeSearchAbsItemId == bookMatch.absItemId) searchQuery else "",
                searchResults = if (activeSearchAbsItemId == bookMatch.absItemId) searchResults else emptyList(),
                isSearching = activeSearchAbsItemId == bookMatch.absItemId && isSearching,
                onActivateSearch = { onActivateSearch(bookMatch.absItemId) },
                onSearchQueryChange = onSearchQueryChange,
                onSelectBook = { id, title, author, duration ->
                    onSelectBook(bookMatch.absItemId, id, title, author, duration)
                },
                onClearMapping = { onClearMapping(bookMatch.absItemId) },
                loadingBookItemId = loadingBookItemId,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun BookMappingCard(
    bookMatch: ABSBookMatch,
    selectedDisplay: SelectedBookDisplay?,
    isSearchActive: Boolean,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    onActivateSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectBook: (String, String, String?, Long?) -> Unit,
    onClearMapping: () -> Unit,
    modifier: Modifier = Modifier,
    loadingBookItemId: String? = null,
) {
    val hasSelection = selectedDisplay != null

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (hasSelection) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ABS book info header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookMatch.absTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    bookMatch.absAuthor?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Status indicator
                if (hasSelection) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Matched",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Selection area with animation
            AnimatedContent(
                targetState = hasSelection,
                label = "selection_state",
            ) { showSelected ->
                if (showSelected && selectedDisplay != null) {
                    // Show selected book with clear option
                    SelectedBookChip(
                        display = selectedDisplay,
                        onClear = onClearMapping,
                    )
                } else {
                    // Show search field
                    BookSearchField(
                        suggestions = bookMatch.suggestions,
                        searchQuery = searchQuery,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        isActive = isSearchActive,
                        onActivate = onActivateSearch,
                        onQueryChange = onSearchQueryChange,
                        onSelectBook = onSelectBook,
                        loadingItemId = loadingBookItemId,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedBookChip(
    display: SelectedBookDisplay,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = display.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                display.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Change selection",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BookSearchField(
    suggestions: List<ABSBookSuggestion>,
    searchQuery: String,
    searchResults: List<SearchHitResponse>,
    isSearching: Boolean,
    isActive: Boolean,
    onActivate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectBook: (String, String, String?, Long?) -> Unit,
    modifier: Modifier = Modifier,
    loadingItemId: String? = null,
) {
    // Combine suggestions with search results
    // Show suggestions when query is empty, search results when typing
    val displayResults: List<BookSearchItem> =
        if (searchQuery.isEmpty() && suggestions.isNotEmpty()) {
            suggestions.map { suggestion ->
                BookSearchItem(
                    id = suggestion.bookId,
                    title = suggestion.title,
                    author = suggestion.author,
                    durationMs = suggestion.durationMs,
                    isSuggestion = true,
                )
            }
        } else {
            searchResults.map { hit ->
                BookSearchItem(
                    id = hit.id,
                    title = hit.name,
                    author = hit.author,
                    durationMs = hit.duration,
                    isSuggestion = false,
                )
            }
        }

    Column(modifier = modifier) {
        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = { query ->
                if (!isActive) onActivate()
                onQueryChange(query)
            },
            results = displayResults,
            onResultSelected = { item ->
                onSelectBook(item.id, item.title, item.author, item.durationMs)
            },
            onSubmit = { query ->
                // Select top result if available
                displayResults.firstOrNull()?.let { item ->
                    onSelectBook(item.id, item.title, item.author, item.durationMs)
                }
            },
            resultContent = { item ->
                BookSearchResultItem(
                    item = item,
                    onClick = { onSelectBook(item.id, item.title, item.author, item.durationMs) },
                    isLoading = loadingItemId == item.id,
                )
            },
            placeholder =
                if (suggestions.isNotEmpty()) {
                    "Tap to see suggestions or search..."
                } else {
                    "Search your library..."
                },
            isLoading = isSearching,
        )

        // Show hint when field is empty but has suggestions
        AnimatedVisibility(
            visible = searchQuery.isEmpty() && suggestions.isNotEmpty() && !isActive,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = "${suggestions.size} suggestion${if (suggestions.size != 1) "s" else ""} available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/**
 * Unified data class for search results and suggestions.
 */
private data class BookSearchItem(
    val id: String,
    val title: String,
    val author: String?,
    val durationMs: Long?,
    val isSuggestion: Boolean,
)

@Composable
private fun BookSearchResultItem(
    item: BookSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    val durationText =
        item.durationMs?.let { ms ->
            val hours = ms / 3_600_000
            val mins = ms % 3_600_000 / 60_000
            if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        }

    AutocompleteResultItem(
        name = item.title,
        subtitle = listOfNotNull(item.author, durationText).joinToString(" · "),
        onClick = onClick,
        modifier = modifier,
        isLoading = isLoading,
        leadingIcon = {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint =
                    if (item.isSuggestion) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

// === Import Options ===

@Composable
@Suppress("LongMethod")
private fun ImportOptionsContent(
    state: ABSImportUiState.Ready,
    onImportSessionsChange: (Boolean) -> Unit,
    onImportProgressChange: (Boolean) -> Unit,
    onRebuildProgressChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Choose what to import from the Audiobookshelf backup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Ready to Import",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(text = "${state.sessionsReady} listening sessions")
                Text(text = "${state.progressReady} book progress records")
                Text(text = "For ${state.usersMatched} users")
            }
        }

        // Options
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Import Options",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.importSessions,
                        onCheckedChange = onImportSessionsChange,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Import listening sessions",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Individual listening events with timestamps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.importProgress,
                        onCheckedChange = onImportProgressChange,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Import book progress",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Current position and completion status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.rebuildProgress,
                        onCheckedChange = onRebuildProgressChange,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Rebuild progress after import",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Recalculate listening stats from events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.analysisWarnings.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "Warnings",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    state.analysisWarnings.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onImport,
            text = "Start Import",
            enabled = state.importSessions || state.importProgress,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// === Importing ===

@Composable
private fun ImportingContent(modifier: Modifier = Modifier) {
    FullScreenLoadingIndicator(
        modifier = modifier,
        message = "Importing Data...",
    )
}

// === Results ===

@Composable
@Suppress("LongMethod", "CognitiveComplexMethod")
private fun ResultsContent(
    state: ABSImportUiState.Ready,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val results = state.importResults
        val hasErrors = results?.errors?.isNotEmpty() == true

        // Success/Error header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (hasErrors) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hasErrors) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint =
                        if (hasErrors) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                )
                Column {
                    Text(
                        text = if (hasErrors) "Import Completed with Issues" else "Import Successful",
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (hasErrors) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                    )
                    results?.duration?.let {
                        Text(
                            text = "Completed in $it",
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (hasErrors) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                        )
                    }
                }
            }
        }

        // Import summary
        results?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Import Summary",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(text = "${r.sessionsImported} sessions imported (${r.sessionsSkipped} skipped)")
                    Text(text = "${r.progressImported} progress records imported (${r.progressSkipped} skipped)")
                    Text(text = "${r.eventsCreated} events created")
                    Text(text = "${r.affectedUsers} users updated")
                }
            }

            if (r.warnings.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Warnings",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        r.warnings.take(5).forEach { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (r.warnings.size > 5) {
                            Text(
                                text = "...and ${r.warnings.size - 5} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (r.errors.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Errors",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        r.errors.take(5).forEach { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (r.errors.size > 5) {
                            Text(
                                text = "...and ${r.errors.size - 5} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onDone,
            text = "Done",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// === Common Components ===

@Composable
private fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ConfidenceBadge(
    confidence: String,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor, label) =
        when (confidence.lowercase()) {
            "exact" -> {
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Exact",
                )
            }

            "high" -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    "High",
                )
            }

            "medium" -> {
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Medium",
                )
            }

            "low" -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Low",
                )
            }

            else -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    confidence.replaceFirstChar { it.uppercase() },
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
