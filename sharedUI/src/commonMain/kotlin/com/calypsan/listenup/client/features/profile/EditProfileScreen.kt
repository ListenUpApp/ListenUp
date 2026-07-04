package com.calypsan.listenup.client.features.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.imePadding
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.calypsan.listenup.client.design.components.ListenUpExtendedFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.components.rememberUserAvatarImage
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.presentation.profile.AvatarChange
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_save_changes
import listenup.composeapp.generated.resources.profile_avatar
import listenup.composeapp.generated.resources.profile_avatar_description
import listenup.composeapp.generated.resources.profile_change_password
import listenup.composeapp.generated.resources.profile_current_password
import listenup.composeapp.generated.resources.profile_edit_profile_title
import listenup.composeapp.generated.resources.profile_name
import listenup.composeapp.generated.resources.profile_name_description
import listenup.composeapp.generated.resources.profile_new_password
import listenup.composeapp.generated.resources.profile_password_description
import listenup.composeapp.generated.resources.profile_remove_photo
import listenup.composeapp.generated.resources.profile_tagline
import listenup.composeapp.generated.resources.profile_tagline_char_count
import listenup.composeapp.generated.resources.profile_tagline_description
import listenup.composeapp.generated.resources.profile_upload_photo
import listenup.composeapp.generated.resources.auth_confirm_password
import listenup.composeapp.generated.resources.auth_first_name
import listenup.composeapp.generated.resources.auth_last_name

private val CARD_CONTENT_PADDING = 20.dp
private val SECTION_GAP = 16.dp
private val AVATAR_SCALLOP_SIZE = 92.dp
private val DESKTOP_MAX_WIDTH = 1040.dp

/**
 * Edit Profile screen — one-save, all inputs via [ListenUpTextField], responsive
 * (compact = single column, medium/expanded = 2-col grid), commonMain.
 *
 * The Android photo picker is wired by the caller via [onPickImage] so this
 * composable stays platform-free.
 *
 * @param onBack Navigate back.
 * @param onPickImage Launch the platform image picker (Android: Photo Picker).
 * @param viewModel The shared [EditProfileViewModel].
 */
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // Redirect straight back to the profile screen on save — the refreshed profile is
                // the confirmation. (Previously awaited a snackbar first, whose suspend delayed the
                // navigation by the snackbar's full duration, and which wasn't visible post-nav anyway.)
                is EditProfileEvent.SaveSucceeded -> {
                    onBack()
                }

                is EditProfileEvent.SaveFailed -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    val isExpanded =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    val ready = state as? EditProfileUiState.Ready

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.profile_edit_profile_title),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Dock Save in the bottomBar slot so ListenUpScaffold's mini-player clearance spacer sits
        // directly beneath it — the floatingActionButton slot does not clear the mini-player overlay.
        bottomBar = {
            if (ready != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ListenUpExtendedFab(
                        onClick = viewModel::save,
                        icon = Icons.Default.Check,
                        text = stringResource(Res.string.common_save_changes),
                        enabled = ready.isDirty && !ready.isSaving,
                        isLoading = ready.isSaving,
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val current = state) {
                is EditProfileUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is EditProfileUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is EditProfileUiState.Ready -> {
                    EditProfileContent(
                        ready = current,
                        isExpanded = isExpanded,
                        onPickImage = onPickImage,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

// ── Content ────────────────────────────────────────────────────────────────────

@Composable
private fun EditProfileContent(
    ready: EditProfileUiState.Ready,
    isExpanded: Boolean,
    onPickImage: () -> Unit,
    viewModel: EditProfileViewModel,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = DESKTOP_MAX_WIDTH)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            if (isExpanded) {
                ExpandedLayout(ready = ready, onPickImage = onPickImage, viewModel = viewModel)
            } else {
                CompactLayout(ready = ready, onPickImage = onPickImage, viewModel = viewModel)
            }
        }
    }
}

// ── Compact (mobile) layout — single column ───────────────────────────────────

@Composable
private fun CompactLayout(
    ready: EditProfileUiState.Ready,
    onPickImage: () -> Unit,
    viewModel: EditProfileViewModel,
) {
    AvatarCard(
        ready = ready,
        onPickImage = onPickImage,
        viewModel = viewModel,
    )
    TaglineCard(ready = ready, viewModel = viewModel)
    NameCard(ready = ready, viewModel = viewModel)
    PasswordCard(ready = ready, viewModel = viewModel)
}

// ── Expanded (medium/large) layout — 2-col grid ───────────────────────────────

@Composable
private fun ExpandedLayout(
    ready: EditProfileUiState.Ready,
    onPickImage: () -> Unit,
    viewModel: EditProfileViewModel,
) {
    // Avatar — full width
    AvatarCard(
        ready = ready,
        onPickImage = onPickImage,
        viewModel = viewModel,
        modifier = Modifier.fillMaxWidth(),
    )
    // Tagline + Name — side by side
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        TaglineCard(
            ready = ready,
            viewModel = viewModel,
            modifier = Modifier.weight(1f),
        )
        NameCard(
            ready = ready,
            viewModel = viewModel,
            modifier = Modifier.weight(1f),
        )
    }
    // Password — full width
    PasswordCard(
        ready = ready,
        viewModel = viewModel,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Section card shell ─────────────────────────────────────────────────────────

/**
 * The mockup's PSection: a surface-low rounded card with a bold [title], optional [subtitle],
 * and arbitrary [content] below a top gap.
 */
@Composable
private fun ProfileSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(CARD_CONTENT_PADDING)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            content()
        }
    }
}

// ── Avatar section ─────────────────────────────────────────────────────────────

@Composable
private fun AvatarCard(
    ready: EditProfileUiState.Ready,
    onPickImage: () -> Unit,
    viewModel: EditProfileViewModel,
    modifier: Modifier = Modifier,
) {
    ProfileSectionCard(
        title = stringResource(Res.string.profile_avatar),
        subtitle = stringResource(Res.string.profile_avatar_description),
        modifier = modifier,
    ) {
        AvatarEditRow(
            user = ready.user,
            avatarChange = ready.avatarChange,
            isSaving = ready.isSaving,
            onPickImage = onPickImage,
            onRevert = viewModel::stageAvatarRevert,
        )
    }
}

@Composable
private fun AvatarEditRow(
    user: User,
    avatarChange: AvatarChange,
    isSaving: Boolean,
    onPickImage: () -> Unit,
    onRevert: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AvatarPreview(
            user = user,
            avatarChange = avatarChange,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPickImage,
                enabled = !isSaving,
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(Res.string.profile_upload_photo))
            }

            // Show Remove only when there's a real image avatar or one staged for upload
            val showRemove =
                user.hasImageAvatar || avatarChange is AvatarChange.Upload
            if (showRemove) {
                TextButton(
                    onClick = onRevert,
                    enabled = !isSaving,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(Res.string.profile_remove_photo))
                }
            }
        }
    }
}

@Composable
private fun AvatarPreview(
    user: User,
    avatarChange: AvatarChange,
) {
    val size = AVATAR_SCALLOP_SIZE
    val context = LocalPlatformContext.current

    when (avatarChange) {
        // Staged upload → preview the picked bytes directly (not yet saved to disk or server).
        is AvatarChange.Upload -> {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(avatarChange.bytes)
                        .memoryCacheKey("staged-${avatarChange.bytes.size}-${avatarChange.bytes.contentHashCode()}")
                        .build(),
                contentDescription = null,
                modifier = Modifier.size(size).clip(cookieScallopShape()),
                contentScale = ContentScale.Crop,
            )
        }

        // Staged revert → always show initials, ignoring any persisted photo.
        AvatarChange.RevertToAuto -> {
            InitialsScallop(user = user, size = size)
        }

        // No staged change → resolve the persisted avatar the same reactive way as every other
        // avatar: a synced change or a completed download flips it to the photo in real time.
        AvatarChange.None -> {
            val avatarImage = rememberUserAvatarImage(user.id.value)
            if (avatarImage != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(avatarImage.localPath)
                            .memoryCacheKey(avatarImage.cacheKey)
                            .diskCacheKey(avatarImage.cacheKey)
                            .build(),
                    contentDescription = null,
                    modifier = Modifier.size(size).clip(cookieScallopShape()),
                    contentScale = ContentScale.Crop,
                )
            } else {
                InitialsScallop(user = user, size = size)
            }
        }
    }
}

/** Initials scallop — outer ring (brand tint) + inner tonal fill with the user's initials. */
@Composable
private fun InitialsScallop(
    user: User,
    size: Dp,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        ScallopBadge(
            size = size,
            containerColor = MaterialTheme.colorScheme.primary,
        ) {}
        ScallopBadge(
            size = size - 8.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = user.initials,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// ── Tagline section ────────────────────────────────────────────────────────────

@Composable
private fun TaglineCard(
    ready: EditProfileUiState.Ready,
    viewModel: EditProfileViewModel,
    modifier: Modifier = Modifier,
) {
    ProfileSectionCard(
        title = stringResource(Res.string.profile_tagline),
        subtitle = stringResource(Res.string.profile_tagline_description),
        modifier = modifier,
    ) {
        ListenUpTextField(
            value = ready.tagline,
            onValueChange = viewModel::setTagline,
            label = stringResource(Res.string.profile_tagline),
            enabled = !ready.isSaving,
            supportingText =
                stringResource(
                    Res.string.profile_tagline_char_count,
                    ready.tagline.length,
                    EditProfileViewModel.MAX_TAGLINE_LENGTH,
                ),
        )
    }
}

// ── Name section ───────────────────────────────────────────────────────────────

@Composable
private fun NameCard(
    ready: EditProfileUiState.Ready,
    viewModel: EditProfileViewModel,
    modifier: Modifier = Modifier,
) {
    ProfileSectionCard(
        title = stringResource(Res.string.profile_name),
        subtitle = stringResource(Res.string.profile_name_description),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListenUpTextField(
                value = ready.firstName,
                onValueChange = viewModel::setFirstName,
                label = stringResource(Res.string.auth_first_name),
                enabled = !ready.isSaving,
            )
            ListenUpTextField(
                value = ready.lastName,
                onValueChange = viewModel::setLastName,
                label = stringResource(Res.string.auth_last_name),
                enabled = !ready.isSaving,
            )
        }
    }
}

// ── Password section ───────────────────────────────────────────────────────────

@Composable
private fun PasswordCard(
    ready: EditProfileUiState.Ready,
    viewModel: EditProfileViewModel,
    modifier: Modifier = Modifier,
) {
    ProfileSectionCard(
        title = stringResource(Res.string.profile_change_password),
        subtitle = stringResource(Res.string.profile_password_description),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PasswordInputField(
                value = ready.currentPassword,
                onValueChange = viewModel::setCurrentPassword,
                label = stringResource(Res.string.profile_current_password),
                enabled = !ready.isSaving,
            )
            PasswordInputField(
                value = ready.newPassword,
                onValueChange = viewModel::setNewPassword,
                label = stringResource(Res.string.profile_new_password),
                enabled = !ready.isSaving,
            )
            PasswordInputField(
                value = ready.confirmPassword,
                onValueChange = viewModel::setConfirmPassword,
                label = stringResource(Res.string.auth_confirm_password),
                enabled = !ready.isSaving,
            )
        }
    }
}

@Composable
private fun PasswordInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by rememberSaveable { mutableStateOf(false) }

    ListenUpTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        onTrailingClick = { visible = !visible },
    )
}
