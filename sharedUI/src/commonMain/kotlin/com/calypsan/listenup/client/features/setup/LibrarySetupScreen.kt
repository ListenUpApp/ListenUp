package com.calypsan.listenup.client.features.setup

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.features.auth.components.BrandMark
import com.calypsan.listenup.client.features.setup.components.FolderRow
import com.calypsan.listenup.client.features.setup.components.LibrarySummaryCard
import com.calypsan.listenup.client.features.setup.components.ScallopBadge
import com.calypsan.listenup.client.features.setup.components.SetupBreadcrumb
import com.calypsan.listenup.client.features.setup.components.SetupHeroBlob
import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import org.koin.compose.viewmodel.koinViewModel

private val SplitThreshold = 840.dp

/**
 * Library-setup wizard — choose audiobook folders, create a library, then confirm.
 *
 * Adaptive from the actual available width (via [BoxWithConstraints]): a phone shell
 * below [SplitThreshold], a brand/content split panel at or above it. Supports the
 * multi-library loop driven by [LibrarySetupViewModel.navActions]; this is a visual
 * redesign over unchanged state.
 */
@Composable
fun LibrarySetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibrarySetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navActions.collect { action ->
            when (action) {
                is LibrarySetupNavAction.LibraryCreated -> Unit
                LibrarySetupNavAction.Finished -> onSetupComplete()
            }
        }
    }

    // Errors surface on the global ErrorBus from the VM; clear the local flag so it doesn't re-latch.
    LaunchedEffect(state.error) {
        if (state.error != null) viewModel.clearError()
    }

    val showConfirmation = state.createdLibraries.isNotEmpty() && state.selectedPaths.isEmpty()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            val isWide = maxWidth >= SplitThreshold
            when {
                state.isCheckingStatus || (!state.needsSetup && !showConfirmation) -> {
                    FullScreenLoadingIndicator()
                }

                isWide -> {
                    DesktopLayout(
                        state = state,
                        showConfirmation = showConfirmation,
                        onOpen = viewModel::loadDirectory,
                        onToggle = viewModel::togglePath,
                        onSelectCurrent = { viewModel.selectPath(state.currentPath) },
                        onBack = viewModel::navigateUp,
                        onContinue = viewModel::createLibrary,
                        onAddAnother = { viewModel.loadDirectory("/") },
                        onDone = viewModel::finishOnboarding,
                    )
                }

                else -> {
                    PhoneLayout(
                        state = state,
                        showConfirmation = showConfirmation,
                        onOpen = viewModel::loadDirectory,
                        onToggle = viewModel::togglePath,
                        onSelectCurrent = { viewModel.selectPath(state.currentPath) },
                        onContinue = viewModel::createLibrary,
                        onAddAnother = { viewModel.loadDirectory("/") },
                        onDone = viewModel::finishOnboarding,
                    )
                }
            }
        }
    }
}

// ──────────────────────────── PHONE ────────────────────────────

@Composable
private fun PhoneLayout(
    state: LibrarySetupUiState,
    showConfirmation: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    onContinue: () -> Unit,
    onAddAnother: () -> Unit,
    onDone: () -> Unit,
) {
    if (showConfirmation) {
        ConfirmationContent(state = state, onDone = onDone, onAddAnother = onAddAnother, wide = false)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            SetupHeroBlob(modifier = Modifier.offset(x = 250.dp, y = (-60).dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            ) {
                BrandMark(onColor = true)
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Choose your audiobook folders",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Select one or more folders where your audiobooks are stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }
        }

        SetupBreadcrumb(
            path = state.currentPath,
            onNavigate = onOpen,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FolderList(
                state = state,
                onOpen = onOpen,
                onToggle = onToggle,
                onSelectCurrent = onSelectCurrent,
                listBottomPadding = 96.dp,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = state.selectedPaths.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                DockedSelectionBar(state = state, onContinue = onContinue)
            }
        }
    }
}

@Composable
private fun DockedSelectionBar(
    state: LibrarySetupUiState,
    onContinue: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val n = state.selectedPaths.size
                Text(
                    text = if (n == 1) "1 folder selected" else "$n folders selected",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.selectedPaths.firstOrNull() ?: state.currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ListenUpButton(
                text = "Continue",
                onClick = onContinue,
                isLoading = state.isCreatingLibrary,
                fillMaxWidth = false,
                trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
            )
        }
    }
}

// ──────────────────────────── DESKTOP ────────────────────────────

@Composable
private fun DesktopLayout(
    state: LibrarySetupUiState,
    showConfirmation: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onAddAnother: () -> Unit,
    onDone: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            val brandWidth = (maxWidth * 0.42f).coerceIn(360.dp, 560.dp)
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(brandWidth)
                        .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                SetupHeroBlob(modifier = Modifier.offset(x = 280.dp, y = (-90).dp))
                Column(
                    modifier = Modifier.fillMaxSize().systemBarsPadding().padding(52.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    BrandMark(onColor = true)
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "Point ListenUp at your audiobooks.",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text =
                            "Pick the folders on your server where your audiobook files live — " +
                                "we'll scan them and build your library automatically.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .systemBarsPadding()
                    .padding(48.dp),
        ) {
            if (showConfirmation) {
                ConfirmationContent(state = state, onDone = onDone, onAddAnother = onAddAnother, wide = true)
            } else {
                DesktopPickerPanel(
                    state = state,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onSelectCurrent = onSelectCurrent,
                    onBack = onBack,
                    onContinue = onContinue,
                )
            }
        }
    }
}

@Composable
private fun DesktopPickerPanel(
    state: LibrarySetupUiState,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Choose your folders",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Select one or more folders where your audiobooks are stored.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        SetupBreadcrumb(path = state.currentPath, onNavigate = onOpen)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(10.dp),
        ) {
            FolderList(
                state = state,
                onOpen = onOpen,
                onToggle = onToggle,
                onSelectCurrent = onSelectCurrent,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val n = state.selectedPaths.size
                Text(
                    text = if (n == 1) "1 folder selected" else "$n folders selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.selectedPaths.firstOrNull() ?: state.currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!state.isRoot) {
                ListenUpButton(text = "Back", onClick = onBack, filled = false, fillMaxWidth = false)
            }
            ListenUpButton(
                text = "Continue",
                onClick = onContinue,
                enabled = state.selectedPaths.isNotEmpty(),
                isLoading = state.isCreatingLibrary,
                fillMaxWidth = false,
                trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
            )
        }
    }
}

// ──────────────────────────── SHARED ────────────────────────────

@Composable
private fun FolderList(
    state: LibrarySetupUiState,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    modifier: Modifier = Modifier,
    listBottomPadding: Dp = 0.dp,
) {
    when {
        state.isLoadingDirectories -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) { FullScreenLoadingIndicator() }
        }

        state.directories.isEmpty() -> {
            EmptyFolder(onSelectCurrent = onSelectCurrent, modifier = modifier)
        }

        else -> {
            LazyColumn(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = listBottomPadding),
            ) {
                items(items = state.directories, key = { it.path }) { entry ->
                    FolderRow(
                        entry = entry,
                        selected = entry.path in state.selectedPaths,
                        onToggle = { onToggle(entry.path) },
                        modifier =
                            Modifier.clickable {
                                if (entry.hasChildren) onOpen(entry.path) else onToggle(entry.path)
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFolder(
    onSelectCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No subfolders here",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This folder has no subfolders. You can select it as a library location.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        ListenUpButton(text = "Select this folder", onClick = onSelectCurrent, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConfirmationContent(
    state: LibrarySetupUiState,
    onDone: () -> Unit,
    onAddAnother: () -> Unit,
    wide: Boolean,
) {
    val library = state.createdLibraries.last()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = if (wide) 0.dp else 26.dp)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (!wide) Spacer(Modifier.height(40.dp))
        Box {
            ScallopBadge(size = if (wide) 108.dp else 132.dp, fill = MaterialTheme.colorScheme.tertiaryContainer) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(if (wide) 56.dp else 66.dp),
                )
            }
            ScallopBadge(
                size = if (wide) 40.dp else 44.dp,
                fill = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (wide) 20.dp else 22.dp),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Library created!",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your audiobooks will be scanned and available shortly.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(28.dp))
        LibrarySummaryCard(
            name = library.name,
            folderCount = library.folders.size,
            firstPath = library.folders.firstOrNull()?.rootPath,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        Spacer(Modifier.height(30.dp))
        ConfirmationActions(onDone = onDone, onAddAnother = onAddAnother, wide = wide)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ConfirmationActions(
    onDone: () -> Unit,
    onAddAnother: () -> Unit,
    wide: Boolean,
) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ListenUpButton(
                text = "Done",
                onClick = onDone,
                fillMaxWidth = false,
                leadingIcon = Icons.Rounded.CheckCircle,
            )
            ListenUpButton(
                text = "Add another library",
                onClick = onAddAnother,
                filled = false,
                fillMaxWidth = false,
                leadingIcon = Icons.Rounded.Add,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListenUpButton(
                text = "Done",
                onClick = onDone,
                leadingIcon = Icons.Rounded.CheckCircle,
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAddAnother).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add another library",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
