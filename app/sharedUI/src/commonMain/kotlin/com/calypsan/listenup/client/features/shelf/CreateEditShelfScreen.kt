package com.calypsan.listenup.client.features.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfNavAction
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_save_changes
import listenup.composeapp.generated.resources.common_shelf_name_hint
import listenup.composeapp.generated.resources.shelf_about_shelves
import listenup.composeapp.generated.resources.shelf_about_shelves_body
import listenup.composeapp.generated.resources.shelf_breadcrumb_library_shelves
import listenup.composeapp.generated.resources.shelf_create_shelf_title
import listenup.composeapp.generated.resources.shelf_delete_shelf
import listenup.composeapp.generated.resources.shelf_description_optional
import listenup.composeapp.generated.resources.shelf_edit_shelf_title
import listenup.composeapp.generated.resources.shelf_form_name
import listenup.composeapp.generated.resources.shelf_preview
import listenup.composeapp.generated.resources.shelf_private_shelf
import listenup.composeapp.generated.resources.shelf_private_shelf_description
import listenup.composeapp.generated.resources.shelf_shelf_details
import listenup.composeapp.generated.resources.shelf_this_will_permanently_delete_this
import listenup.composeapp.generated.resources.shelf_visibility
import listenup.composeapp.generated.resources.shelf_visible_to_anyone
import listenup.composeapp.generated.resources.shelf_whats_this_shelf_for
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Bundles the mutable form state and callbacks passed between the screen and layout
 * composables, keeping layout function signatures within the detekt [LongParameterList]
 * threshold.
 */
private data class ShelfFormState(
    val isEditing: Boolean,
    val name: String,
    val description: String,
    val isPrivate: Boolean,
    val isSaving: Boolean,
    val canSave: Boolean,
    val onBack: () -> Unit,
    val onDeleteClick: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onPrivateChange: (Boolean) -> Unit,
    val onSave: () -> Unit,
)

/**
 * Screen for creating or editing a shelf — M3 Expressive redesign.
 *
 * Layout: a `primaryContainer` colour-block hero (back button, breadcrumb, title, action
 * icon), a scrollable form body with two [SectionGroup]s (Shelf Details, Visibility),
 * a live shelf preview tile, a primary Save action, and — in edit mode — a destructive
 * Delete (confirmed via [ListenUpDestructiveDialog]).
 *
 * Wide layout (≥ [TwoPaneMinWidth]): the hero becomes a horizontal bar and the form body
 * + preview/save column sit side-by-side in a [Row].
 *
 * @param shelfId If non-null, edit this shelf; if null, create a new shelf.
 * @param onBack Navigation callback invoked on save/delete success and on the back button.
 * @param viewModel [CreateEditShelfViewModel] injected via Koin.
 */
@Composable
fun CreateEditShelfScreen(
    shelfId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateEditShelfViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val isEditing = shelfId != null

    // Screen-owned text state: seeded once from Loaded, keyed on shelfId so a
    // navigation-to-new-shelf discards stale saved text.
    var name by rememberSaveable(shelfId) { mutableStateOf("") }
    var description by rememberSaveable(shelfId) { mutableStateOf("") }
    var isPrivate by rememberSaveable(shelfId) { mutableStateOf(false) }
    var seeded by rememberSaveable(shelfId) { mutableStateOf(false) }

    LaunchedEffect(shelfId) {
        if (shelfId != null) viewModel.initEdit(shelfId) else viewModel.initCreate()
    }

    LaunchedEffect(state) {
        val current = state
        if (current is CreateEditShelfUiState.Loaded && !seeded) {
            name = current.name
            description = current.description
            isPrivate = current.isPrivate
            seeded = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                CreateEditShelfNavAction.NavigateBack -> onBack()
            }
        }
    }

    LaunchedEffect(state) {
        val current = state
        if (current is CreateEditShelfUiState.Error) {
            snackbarHostState.showSnackbar(current.message)
            viewModel.dismissError()
        }
    }

    val isLoading = state is CreateEditShelfUiState.LoadingExisting
    val isSaving = state is CreateEditShelfUiState.Saving

    val formState =
        ShelfFormState(
            isEditing = isEditing,
            name = name,
            description = description,
            isPrivate = isPrivate,
            isSaving = isSaving,
            canSave = name.isNotBlank() && !isSaving,
            onBack = onBack,
            onDeleteClick = { showDeleteDialog = true },
            onNameChange = { name = it },
            onDescriptionChange = { description = it },
            onPrivateChange = { isPrivate = it },
            onSave = { viewModel.save(name, description, isPrivate) },
        )

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isWide = windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())

    if (showDeleteDialog) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(Res.string.shelf_delete_shelf),
            text = stringResource(Res.string.shelf_this_will_permanently_delete_this),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = { showDeleteDialog = false },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> FullScreenLoadingIndicator()
            isWide -> ShelfFormWideLayout(formState = formState)
            else -> ShelfFormPhoneLayout(formState = formState)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ─────────────────────────── Phone layout ────────────────────────────

@Composable
private fun ShelfFormPhoneLayout(
    formState: ShelfFormState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
    ) {
        ShelfHero(
            isEditing = formState.isEditing,
            isWide = false,
            onBack = formState.onBack,
            onDeleteClick = formState.onDeleteClick,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ShelfDetailsSection(
                name = formState.name,
                description = formState.description,
                onNameChange = formState.onNameChange,
                onDescriptionChange = formState.onDescriptionChange,
            )

            ShelfVisibilitySection(
                isPrivate = formState.isPrivate,
                onPrivateChange = formState.onPrivateChange,
            )

            ShelfPreviewTile(name = formState.name, isPrivate = formState.isPrivate)

            ListenUpButton(
                text =
                    if (formState.isEditing) {
                        stringResource(Res.string.common_save_changes)
                    } else {
                        stringResource(Res.string.shelf_create_shelf_title)
                    },
                leadingIcon =
                    if (formState.isEditing) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkAdd,
                onClick = formState.onSave,
                enabled = formState.canSave,
                isLoading = formState.isSaving,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ─────────────────────────── Wide layout ─────────────────────────────

@Composable
private fun ShelfFormWideLayout(
    formState: ShelfFormState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ShelfHero(
            isEditing = formState.isEditing,
            isWide = true,
            onBack = formState.onBack,
            onDeleteClick = formState.onDeleteClick,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Left: form fields
            Column(
                modifier =
                    Modifier
                        .weight(1.2f)
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ShelfDetailsSection(
                    name = formState.name,
                    description = formState.description,
                    onNameChange = formState.onNameChange,
                    onDescriptionChange = formState.onDescriptionChange,
                )

                ShelfVisibilitySection(
                    isPrivate = formState.isPrivate,
                    onPrivateChange = formState.onPrivateChange,
                )
            }

            // Right: preview, about, and save action
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ShelfPreviewTile(name = formState.name, isPrivate = formState.isPrivate)

                ShelfAboutCard()

                ListenUpButton(
                    text =
                        if (formState.isEditing) {
                            stringResource(Res.string.common_save_changes)
                        } else {
                            stringResource(Res.string.shelf_create_shelf_title)
                        },
                    leadingIcon =
                        if (formState.isEditing) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkAdd,
                    onClick = formState.onSave,
                    enabled = formState.canSave,
                    isLoading = formState.isSaving,
                )
            }
        }
    }
}

// ─────────────────────────── Hero header ─────────────────────────────

@Composable
private fun ShelfHero(
    isEditing: Boolean,
    isWide: Boolean,
    onBack: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heroShape =
        if (isWide) {
            MaterialTheme.shapes.extraLarge
        } else {
            MaterialTheme.shapes.extraLarge.copy(
                topStart = CornerSize(0.dp),
                topEnd = CornerSize(0.dp),
            )
        }
    val heroModifier =
        if (isWide) {
            modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)
        } else {
            modifier.fillMaxWidth()
        }

    Surface(
        modifier = heroModifier,
        shape = heroShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    start = if (isWide) 32.dp else 0.dp,
                    end = if (isWide) 32.dp else 0.dp,
                    bottom = 26.dp,
                ),
        ) {
            ShelfHeroNavRow(
                isEditing = isEditing,
                isWide = isWide,
                onBack = onBack,
                onDeleteClick = onDeleteClick,
            )
            ShelfHeroTitleBlock(
                isEditing = isEditing,
                isWide = isWide,
            )
        }
    }
}

@Composable
private fun ShelfHeroNavRow(
    isEditing: Boolean,
    isWide: Boolean,
    onBack: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    HeroNavRow(
        onBack = onBack,
        buttonBackground = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
        applyStatusBarInset = !isWide,
        actions = {
            if (isEditing) {
                DeleteHeroButton(isWide = isWide, onClick = onDeleteClick)
            } else {
                ScallopBadge(
                    size = if (isWide) 60.dp else 48.dp,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(if (isWide) 32.dp else 26.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ShelfHeroTitleBlock(
    isEditing: Boolean,
    isWide: Boolean,
) {
    Column(
        modifier = Modifier.padding(horizontal = if (isWide) 0.dp else 22.dp),
    ) {
        Text(
            text = stringResource(Res.string.shelf_breadcrumb_library_shelves),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text =
                if (isEditing) {
                    stringResource(Res.string.shelf_edit_shelf_title)
                } else {
                    stringResource(Res.string.shelf_create_shelf_title)
                },
            style =
                if (isWide) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.headlineLarge
                },
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun DeleteHeroButton(
    isWide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isWide) {
        // Wide: text + icon pill
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(Res.string.common_delete),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    } else {
        // Phone: compact icon button
        IconButton(
            onClick = onClick,
            modifier =
                modifier
                    .size(46.dp)
                    .clip(MaterialTheme.shapes.medium),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.shelf_delete_shelf),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Form sections ───────────────────────────

@Composable
private fun ShelfDetailsSection(
    name: String,
    description: String,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(
        label = stringResource(Res.string.shelf_shelf_details),
        icon = Icons.Outlined.BookmarkAdd,
        accent = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(Res.string.shelf_form_name),
                placeholder = stringResource(Res.string.common_shelf_name_hint),
                leadingIcon = Icons.Outlined.Label,
            )

            ListenUpTextArea(
                value = description,
                onValueChange = onDescriptionChange,
                label = stringResource(Res.string.shelf_description_optional),
                placeholder = stringResource(Res.string.shelf_whats_this_shelf_for),
                minLines = 3,
                maxLines = 5,
            )
        }
    }
}

@Composable
private fun ShelfVisibilitySection(
    isPrivate: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(
        label = stringResource(Res.string.shelf_visibility),
        icon = Icons.Outlined.Visibility,
        accent = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        SettingRow(
            title = stringResource(Res.string.shelf_private_shelf),
            subtitle =
                if (isPrivate) {
                    stringResource(Res.string.shelf_private_shelf_description)
                } else {
                    stringResource(Res.string.shelf_visible_to_anyone)
                },
            icon = if (isPrivate) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            accent = MaterialTheme.colorScheme.tertiary,
            trailing = {
                Switch(
                    checked = isPrivate,
                    onCheckedChange = onPrivateChange,
                )
            },
        )
    }
}

// ─────────────────────────── Preview tile ────────────────────────────

@Composable
private fun ShelfPreviewTile(
    name: String,
    isPrivate: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            ShelfPreviewHeader()
            Spacer(modifier = Modifier.height(14.dp))
            ShelfPreviewContent(name = name, isPrivate = isPrivate)
        }
    }
}

@Composable
private fun ShelfPreviewHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(Res.string.shelf_preview).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = TextUnit(1.2f, TextUnitType.Sp),
        )
    }
}

@Composable
private fun ShelfPreviewContent(
    name: String,
    isPrivate: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name.ifBlank { stringResource(Res.string.shelf_create_shelf_title) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                if (isPrivate) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Text(
                text =
                    if (isPrivate) {
                        stringResource(Res.string.shelf_private_shelf)
                    } else {
                        stringResource(Res.string.shelf_visible_to_anyone)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

// ─────────────────────────── About card (wide only) ──────────────────

@Composable
private fun ShelfAboutCard(modifier: Modifier = Modifier) {
    SectionGroup(
        label = stringResource(Res.string.shelf_about_shelves),
        icon = Icons.Outlined.Info,
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(Res.string.shelf_about_shelves_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
