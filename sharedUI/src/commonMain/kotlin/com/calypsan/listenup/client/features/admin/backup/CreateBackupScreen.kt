package com.calypsan.listenup.client.features.admin.backup

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.admin_book_covers_and_user_avatars
import listenup.composeapp.generated.resources.admin_cover_images
import listenup.composeapp.generated.resources.admin_create_a_backup_of_your
import listenup.composeapp.generated.resources.admin_create_backup
import listenup.composeapp.generated.resources.admin_creating_backup
import listenup.composeapp.generated.resources.admin_events_sessions_and_progress_data
import listenup.composeapp.generated.resources.admin_listening_history
import listenup.composeapp.generated.resources.admin_recommended_for_full_restore_capability
import listenup.composeapp.generated.resources.admin_significantly_increases_backup_size
import listenup.composeapp.generated.resources.admin_what_to_include

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBackupScreen(
    viewModel: AdminBackupViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var includeEvents by remember { mutableStateOf(true) }
    var includeImages by remember { mutableStateOf(false) }
    var hasStartedCreation by remember { mutableStateOf(false) }

    // Screen is only reachable after the list has loaded; the only meaningful
    // state here is Ready. isCreating and error live on Ready.
    val ready = state as? AdminBackupUiState.Ready
    val isCreating = ready?.isCreating == true
    val error = ready?.error

    // Navigate back on success
    LaunchedEffect(isCreating, hasStartedCreation) {
        if (hasStartedCreation && !isCreating && error == null) {
            onSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.admin_create_backup)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (isCreating) {
            CreatingBackupContent(
                modifier = Modifier.padding(paddingValues),
            )
        } else {
            CreateBackupForm(
                includeEvents = includeEvents,
                includeImages = includeImages,
                onIncludeEventsChange = { includeEvents = it },
                onIncludeImagesChange = { includeImages = it },
                error = error,
                onCreateClick = {
                    hasStartedCreation = true
                    viewModel.createBackup(
                        includeImages = includeImages,
                        includeEvents = includeEvents,
                    )
                },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun CreateBackupForm(
    includeEvents: Boolean,
    includeImages: Boolean,
    onIncludeEventsChange: (Boolean) -> Unit,
    onIncludeImagesChange: (Boolean) -> Unit,
    error: String?,
    onCreateClick: () -> Unit,
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
            text = stringResource(Res.string.admin_create_a_backup_of_your),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(Res.string.admin_what_to_include),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Events option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = includeEvents,
                        onCheckedChange = onIncludeEventsChange,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Timeline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(Res.string.admin_listening_history),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Text(
                            text = stringResource(Res.string.admin_events_sessions_and_progress_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.height(16.dp),
                            )
                            Text(
                                text = stringResource(Res.string.admin_recommended_for_full_restore_capability),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Images option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = includeImages,
                        onCheckedChange = onIncludeImagesChange,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                text = stringResource(Res.string.admin_cover_images),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Text(
                            text = stringResource(Res.string.admin_book_covers_and_user_avatars),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.height(16.dp),
                            )
                            Text(
                                text = stringResource(Res.string.admin_significantly_increases_backup_size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "ℹ️",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        if (includeImages) {
                            "Backup will include images. This may take a while and result in a larger file."
                        } else {
                            "Backup will not include images. Estimated size depends on your library."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        // Error display
        error?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onCreateClick,
            text = stringResource(Res.string.admin_create_backup),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun CreatingBackupContent(modifier: Modifier = Modifier) {
    FullScreenLoadingIndicator(message = stringResource(Res.string.admin_creating_backup))
}
