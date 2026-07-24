package com.calypsan.listenup.client.features.admin

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.SelectableOptionCard
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType
import com.calypsan.listenup.client.presentation.admin.CreateInviteField
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_1_day
import listenup.composeapp.generated.resources.admin_access_level
import listenup.composeapp.generated.resources.admin_can_access_the_library
import listenup.composeapp.generated.resources.admin_can_manage_users_and_invites
import listenup.composeapp.generated.resources.admin_create_an_invite_to_share
import listenup.composeapp.generated.resources.admin_create_another
import listenup.composeapp.generated.resources.admin_create_invite
import listenup.composeapp.generated.resources.admin_invite_created
import listenup.composeapp.generated.resources.admin_invite_created_link_copied_to
import listenup.composeapp.generated.resources.admin_invite_expires_in
import listenup.composeapp.generated.resources.admin_invite_preview
import listenup.composeapp.generated.resources.admin_link_copied
import listenup.composeapp.generated.resources.admin_name_is_invited
import listenup.composeapp.generated.resources.admin_whos_joining
import listenup.composeapp.generated.resources.common_admin
import listenup.composeapp.generated.resources.common_copy
import listenup.composeapp.generated.resources.common_done
import listenup.composeapp.generated.resources.common_email_address
import listenup.composeapp.generated.resources.common_member
import listenup.composeapp.generated.resources.common_n_days

private const val ROLE_MEMBER = "member"
private const val ROLE_ADMIN = "admin"
private val EXPIRY_OPTIONS = listOf(1, 7, 30)

/**
 * Create invite screen - Material 3 Expressive form to create new invites: a color-blocked
 * [ColorBlockHero], accent-headed form sections (display name + email fields, two selectable role
 * cards, and an expressive expiry segmented control), and a primary-container link-preview card on
 * success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInviteScreen(
    viewModel: CreateInviteViewModel,
    onBackClick: () -> Unit,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberCopyToClipboard()
    val inviteCreatedMessage = stringResource(Res.string.admin_invite_created_link_copied_to)
    val linkCopiedMessage = stringResource(Res.string.admin_link_copied)

    // Handle success - show link and allow copy; surface non-validation errors in snackbar.
    val readyStatus = (state as? CreateInviteUiState.Ready)?.status
    LaunchedEffect(readyStatus) {
        when (readyStatus) {
            is CreateInviteStatus.Success -> {
                // Auto-copy the link
                copyToClipboard(readyStatus.invite.url)
                snackbarHostState.showSnackbar(inviteCreatedMessage)
            }

            is CreateInviteStatus.Error -> {
                val message =
                    when (val type = readyStatus.type) {
                        is CreateInviteErrorType.ValidationError -> null
                        is CreateInviteErrorType.EmailInUse -> "A user with this email already exists"
                        is CreateInviteErrorType.NetworkError -> type.detail ?: "Network error"
                        is CreateInviteErrorType.ServerError -> type.detail ?: "Server error"
                    }
                message?.let {
                    snackbarHostState.showSnackbar(it)
                    viewModel.clearError()
                }
            }

            else -> {}
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.admin_create_invite),
                badgeIcon = Icons.Outlined.PersonAdd,
                onBack = onBackClick,
                supportingText = stringResource(Res.string.admin_create_an_invite_to_share),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val s = state) {
            is CreateInviteUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is CreateInviteUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is CreateInviteUiState.Ready -> {
                when (val status = s.status) {
                    is CreateInviteStatus.Success -> {
                        SuccessContent(
                            inviteUrl = status.invite.url,
                            inviteName = status.invite.name,
                            onCopyClick = {
                                scope.launch {
                                    copyToClipboard(status.invite.url)
                                    snackbarHostState.showSnackbar(linkCopiedMessage)
                                }
                            },
                            onCreateAnother = { viewModel.reset() },
                            onDone = onSuccess,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }

                    else -> {
                        CreateInviteForm(
                            status = status,
                            onSubmit = viewModel::createInvite,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateInviteForm(
    status: CreateInviteStatus,
    onSubmit: (String, String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ROLE_MEMBER) }
    var expiresInDays by remember { mutableIntStateOf(7) }

    val isSubmitting = status is CreateInviteStatus.Submitting
    val validationField =
        (status as? CreateInviteStatus.Error)
            ?.type
            ?.let { it as? CreateInviteErrorType.ValidationError }
            ?.field

    val isExpanded =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    val sections: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            WhosJoiningSection(
                email = email,
                onEmailChange = { email = it },
                isSubmitting = isSubmitting,
                validationField = validationField,
                onSubmit = { onSubmit(email, role, expiresInDays) },
            )
            AccessLevelSection(
                role = role,
                onRoleChange = { if (!isSubmitting) role = it },
            )
            ExpirySection(
                expiresInDays = expiresInDays,
                isSubmitting = isSubmitting,
                onExpiryChange = { if (!isSubmitting) expiresInDays = it },
            )
        }
    }

    val submitButton: @Composable () -> Unit = {
        ListenUpButton(
            onClick = { onSubmit(email, role, expiresInDays) },
            text = stringResource(Res.string.admin_create_invite),
            leadingIcon = Icons.AutoMirrored.Outlined.Send,
            enabled = !isSubmitting,
            isLoading = isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (isExpanded) {
        Row(
            modifier =
                modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(modifier = Modifier.weight(1.2f)) { sections() }
            Box(modifier = Modifier.weight(1f)) { submitButton() }
        }
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            sections()
            submitButton()
        }
    }
}

@Composable
private fun WhosJoiningSection(
    email: String,
    onEmailChange: (String) -> Unit,
    isSubmitting: Boolean,
    validationField: CreateInviteField?,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(
            label = stringResource(Res.string.admin_whos_joining),
            icon = Icons.Outlined.PersonAdd,
            accent = MaterialTheme.colorScheme.primary,
        )
        ListenUpTextField(
            value = email,
            onValueChange = onEmailChange,
            label = stringResource(Res.string.common_email_address),
            leadingIcon = Icons.Outlined.Mail,
            enabled = !isSubmitting,
            isError = validationField == CreateInviteField.EMAIL,
            supportingText =
                if (validationField == CreateInviteField.EMAIL) {
                    "Valid email is required"
                } else {
                    "Their email address for login"
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (!isSubmitting) onSubmit()
                    },
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AccessLevelSection(
    role: String,
    onRoleChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            label = stringResource(Res.string.admin_access_level),
            icon = Icons.Outlined.Shield,
            accent = MaterialTheme.colorScheme.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectableOptionCard(
                title = stringResource(Res.string.common_member),
                subtitle = stringResource(Res.string.admin_can_access_the_library),
                icon = Icons.Outlined.Headphones,
                selected = role == ROLE_MEMBER,
                onClick = { onRoleChange(ROLE_MEMBER) },
                modifier = Modifier.weight(1f),
            )
            SelectableOptionCard(
                title = stringResource(Res.string.common_admin),
                subtitle = stringResource(Res.string.admin_can_manage_users_and_invites),
                icon = Icons.Outlined.Shield,
                selected = role == ROLE_ADMIN,
                onClick = { onRoleChange(ROLE_ADMIN) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExpirySection(
    expiresInDays: Int,
    isSubmitting: Boolean,
    onExpiryChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            label = stringResource(Res.string.admin_invite_expires_in),
            icon = Icons.Outlined.Schedule,
            accent = MaterialTheme.colorScheme.tertiary,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EXPIRY_OPTIONS.forEachIndexed { index, days ->
                SegmentedButton(
                    selected = expiresInDays == days,
                    onClick = { onExpiryChange(days) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = EXPIRY_OPTIONS.size),
                    enabled = !isSubmitting,
                ) {
                    Text(
                        text =
                            if (days == 1) {
                                stringResource(Res.string.admin_1_day)
                            } else {
                                stringResource(Res.string.common_n_days, days.toString())
                            },
                    )
                }
            }
        }
    }
}

/**
 * Section overline header for the invite form: an accent-tinted [TonalIconTile] beside an UPPERCASE
 * label. Matches the [com.calypsan.listenup.client.design.components.SectionGroup] header look but
 * without the card body, since the invite form sections are free-standing.
 */
@Composable
private fun SectionHeader(
    label: String,
    icon: ImageVector,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TonalIconTile(icon = icon, size = 30.dp, accent = accent)
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

@Composable
private fun SuccessContent(
    inviteUrl: String,
    inviteName: String,
    onCopyClick: () -> Unit,
    onCreateAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExpanded =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )
    val horizontalPadding = if (isExpanded) 24.dp else 16.dp
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinkPreviewCard(
            inviteName = inviteName,
            inviteUrl = inviteUrl,
            onCopyClick = onCopyClick,
        )

        Spacer(modifier = Modifier.height(8.dp))

        ListenUpButton(
            onClick = onDone,
            text = stringResource(Res.string.common_done),
            modifier = Modifier.fillMaxWidth(),
        )

        ListenUpButton(
            onClick = onCreateAnother,
            text = stringResource(Res.string.admin_create_another),
            filled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The expressive invite-link payoff card: a [MaterialTheme.colorScheme.primaryContainer] surface with
 * a [ScallopBadge], the recipient line, and the invite URL in a copyable pill.
 */
@Composable
private fun LinkPreviewCard(
    inviteName: String,
    inviteUrl: String,
    onCopyClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.admin_invite_preview).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ScallopBadge(size = 52.dp, containerColor = colors.primary) {
                    Icon(
                        imageVector = Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = colors.onPrimary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.admin_invite_created),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource(Res.string.admin_name_is_invited, inviteName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = colors.onPrimaryContainer.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = inviteUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ListenUpButton(
                        onClick = onCopyClick,
                        text = stringResource(Res.string.common_copy),
                        leadingIcon = Icons.Outlined.ContentCopy,
                        fillMaxWidth = false,
                    )
                }
            }
        }
    }
}
