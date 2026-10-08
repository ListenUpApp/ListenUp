package com.calypsan.listenup.client.features.admin.backup

import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
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
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import com.calypsan.listenup.client.presentation.error.localized
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.admin_backup_info_with_images
import listenup.composeapp.generated.resources.admin_backup_info_without_images
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_book_covers_and_user_avatars
import listenup.composeapp.generated.resources.admin_cover_images
import listenup.composeapp.generated.resources.admin_create_a_backup_of_your
import listenup.composeapp.generated.resources.admin_create_backup
import listenup.composeapp.generated.resources.admin_creating_backup
import listenup.composeapp.generated.resources.admin_significantly_increases_backup_size
import listenup.composeapp.generated.resources.admin_what_to_include
import androidx.compose.material.icons.outlined.Info
import com.calypsan.listenup.client.design.components.TonalIconTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBackupScreen(
    viewModel: AdminBackupViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    ListenUpScaffold(
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.admin_create_backup),
                onBack = onBackClick,
            )
        },
    ) { paddingValues ->
        if (isCreating) {
            CreatingBackupContent(
                modifier = Modifier.padding(paddingValues),
            )
        } else {
            CreateBackupForm(
                includeImages = includeImages,
                onIncludeImagesChange = { includeImages = it },
                error = error,
                onCreateClick = {
                    hasStartedCreation = true
                    viewModel.createBackup(includeImages = includeImages)
                },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

/**
 * The backup options with their create action. A phone stacks what to include, the summary of what
 * the backup will hold, and the action in one column. From the medium width up the options and the
 * summary sit side by side, the summary ending in the action — the admin sees what the choice means
 * next to the button that commits to it. On a small tablet the two share the width evenly; from the
 * expanded width the summary settles into a fixed side panel and the options take the rest.
 */
@Composable
internal fun CreateBackupForm(
    includeImages: Boolean,
    onIncludeImagesChange: (Boolean) -> Unit,
    error: AppError?,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        val hasRoomForPanel = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
        Row(
            modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(
                            1f,
                        ).fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BackupIntro()
                IncludeOptionsCard(includeImages = includeImages, onIncludeImagesChange = onIncludeImagesChange)
            }
            Column(
                modifier =
                    Modifier
                        .then(if (hasRoomForPanel) Modifier.width(BackupSummaryPanelWidth) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BackupSummaryCard(includeImages = includeImages)
                error?.let { BackupErrorCard(error = it) }
                CreateBackupButton(onClick = onCreateClick)
            }
        }
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupIntro()
            IncludeOptionsCard(includeImages = includeImages, onIncludeImagesChange = onIncludeImagesChange)
            BackupSummaryCard(includeImages = includeImages)
            error?.let { BackupErrorCard(error = it) }
            Spacer(modifier = Modifier.weight(1f))
            CreateBackupButton(onClick = onCreateClick)
        }
    }
}

/** Width of the wide layout's summary panel — one comfortable card column. */
private val BackupSummaryPanelWidth = 360.dp

@Composable
private fun BackupIntro() {
    Text(
        text = stringResource(Res.string.admin_create_a_backup_of_your),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IncludeOptionsCard(
    includeImages: Boolean,
    onIncludeImagesChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(Res.string.admin_what_to_include),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

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
                            imageVector = Icons.Outlined.Warning,
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
}

@Composable
private fun BackupSummaryCard(includeImages: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalIconTile(
                icon = Icons.Outlined.Info,
                accent = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 40.dp,
            )
            Text(
                text =
                    stringResource(
                        if (includeImages) {
                            Res.string.admin_backup_info_with_images
                        } else {
                            Res.string.admin_backup_info_without_images
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun BackupErrorCard(error: AppError) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Text(
            text = error.localized(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

@Composable
private fun CreateBackupButton(onClick: () -> Unit) {
    ListenUpButton(
        onClick = onClick,
        text = stringResource(Res.string.admin_create_backup),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun CreatingBackupContent(modifier: Modifier = Modifier) {
    FullScreenLoadingIndicator(message = stringResource(Res.string.admin_creating_backup))
}
