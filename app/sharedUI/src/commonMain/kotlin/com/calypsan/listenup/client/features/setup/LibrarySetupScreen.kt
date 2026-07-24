package com.calypsan.listenup.client.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.features.auth.components.BrandMark
import com.calypsan.listenup.client.features.setup.components.FolderRow
import com.calypsan.listenup.client.features.setup.components.SetupBreadcrumb
import com.calypsan.listenup.client.features.setup.components.SetupHeroBlob
import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_setup_choose_audiobook_folders
import listenup.composeapp.generated.resources.library_setup_choose_folders_title
import listenup.composeapp.generated.resources.library_setup_folder_selected_count
import listenup.composeapp.generated.resources.library_setup_folders_selected_count
import listenup.composeapp.generated.resources.library_setup_no_subfolders_here
import listenup.composeapp.generated.resources.library_setup_no_subfolders_select_hint
import listenup.composeapp.generated.resources.library_setup_point_at_audiobooks
import listenup.composeapp.generated.resources.library_setup_select_one_or_more
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Library-setup wizard — choose audiobook folders, then confirm.
 *
 * Adaptive from the actual available width (via [BoxWithConstraints]): a phone shell
 * below [TwoPaneMinWidth], a brand/content split panel at or above it. The single
 * primary action is [LibrarySetupViewModel.completeSetup] which registers all selected
 * folders and navigates away via [LibrarySetupNavAction.Finished].
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
                LibrarySetupNavAction.Finished -> onSetupComplete()
            }
        }
    }

    // Errors surface on the global ErrorBus from the VM; clear the local flag so it doesn't re-latch.
    LaunchedEffect(state.error) {
        if (state.error != null) viewModel.clearError()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            val isWide = maxWidth >= TwoPaneMinWidth
            when {
                state.isCheckingStatus || !state.needsSetup -> {
                    FullScreenLoadingIndicator()
                }

                isWide -> {
                    DesktopLayout(
                        state = state,
                        onOpen = viewModel::loadDirectory,
                        onToggle = viewModel::togglePath,
                        onSelectCurrent = { viewModel.selectPath(state.currentPath) },
                        onBack = viewModel::navigateUp,
                        onContinue = viewModel::completeSetup,
                    )
                }

                else -> {
                    PhoneLayout(
                        state = state,
                        onOpen = viewModel::loadDirectory,
                        onToggle = viewModel::togglePath,
                        onSelectCurrent = { viewModel.selectPath(state.currentPath) },
                        onContinue = viewModel::completeSetup,
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
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    onContinue: () -> Unit,
) {
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
                    text = stringResource(Res.string.library_setup_choose_audiobook_folders),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.library_setup_select_one_or_more),
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
                currentPath = state.currentPath,
                directories = state.directories,
                isLoading = state.isLoadingDirectories,
                selectedPaths = state.selectedPaths,
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
                    text =
                        stringResource(
                            if (n ==
                                1
                            ) {
                                Res.string.library_setup_folder_selected_count
                            } else {
                                Res.string.library_setup_folders_selected_count
                            },
                            n,
                        ),
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
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
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
                        text = stringResource(Res.string.library_setup_point_at_audiobooks),
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
            text = stringResource(Res.string.library_setup_choose_folders_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(Res.string.library_setup_select_one_or_more),
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
                currentPath = state.currentPath,
                directories = state.directories,
                isLoading = state.isLoadingDirectories,
                selectedPaths = state.selectedPaths,
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
                    text =
                        stringResource(
                            if (n ==
                                1
                            ) {
                                Res.string.library_setup_folder_selected_count
                            } else {
                                Res.string.library_setup_folders_selected_count
                            },
                            n,
                        ),
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
    currentPath: String,
    directories: List<DirectoryEntry>,
    isLoading: Boolean,
    selectedPaths: Set<String>,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    modifier: Modifier = Modifier,
    listBottomPadding: Dp = 0.dp,
) {
    val slideSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val scaleSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Box(modifier = modifier) {
        AnimatedContent(
            targetState = FolderFrame(currentPath, directories, isLoading),
            contentKey = { it.path },
            transitionSpec = {
                val enterDeeper = depthOf(targetState.path) >= depthOf(initialState.path)
                val direction =
                    if (enterDeeper) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                val enter =
                    slideIntoContainer(direction, animationSpec = slideSpatial) +
                        fadeIn(animationSpec = fade) +
                        scaleIn(initialScale = 0.92f, animationSpec = scaleSpatial)
                val exit =
                    slideOutOfContainer(direction, animationSpec = slideSpatial) +
                        fadeOut(animationSpec = fade) +
                        scaleOut(targetScale = 0.92f, animationSpec = scaleSpatial)
                enter togetherWith exit
            },
            label = "folderNav",
        ) { frame ->
            when {
                frame.isLoading -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { FullScreenLoadingIndicator() }
                }

                frame.directories.isEmpty() -> {
                    EmptyFolder(onSelectCurrent = onSelectCurrent, modifier = Modifier.fillMaxSize())
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = listBottomPadding),
                    ) {
                        items(items = frame.directories, key = { it.path }) { entry ->
                            FolderRow(
                                entry = entry,
                                selected = entry.path in selectedPaths,
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
    }
}

/** The animated unit for the folder picker — captured per-pane so the outgoing slide keeps its own list. */
private data class FolderFrame(
    val path: String,
    val directories: List<DirectoryEntry>,
    val isLoading: Boolean,
)

/** Monotonic depth proxy: deeper paths have more separators, so it tells us slide direction. */
private fun depthOf(path: String): Int = path.count { it == '/' }

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
            text = stringResource(Res.string.library_setup_no_subfolders_here),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.library_setup_no_subfolders_select_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        ListenUpButton(text = "Select this folder", onClick = onSelectCurrent, modifier = Modifier.fillMaxWidth())
    }
}
