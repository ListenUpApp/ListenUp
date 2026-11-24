package com.calypsan.listenup.client.features.admin

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.design.components.ActionTile
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.SaveAction
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.util.rememberUnsavedWorkGuard
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_link_copied
import listenup.composeapp.generated.resources.admin_no_pending_password_resets
import listenup.composeapp.generated.resources.admin_password_resets
import listenup.composeapp.generated.resources.admin_reset_code_copied
import listenup.composeapp.generated.resources.common_administration
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.calypsan.listenup.client.design.components.rememberHeroScrollBehavior

/**
 * Combined admin screen showing server settings, users, pending registrations & invites, and the
 * management actions. Material 3 Expressive reskin: a color-blocked hero header, accent-headed
 * [SectionGroup] cards composed of [SettingRow]s, and color-blocked [ActionTile]s for management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    focus: AdminFocus = AdminFocus.TOP,
    onBackClick: () -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit = {},
    onCategoriesClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onImportClick: () -> Unit = {},
    onUploadBooksClick: () -> Unit = {},
    onInboxClick: () -> Unit = {},
    onLibrarySettingsClick: () -> Unit = {},
    onOrganizeClick: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    serverName: String = "",
    onServerNameChange: (String) -> Unit = {},
    remoteUrl: String = "",
    onRemoteUrlChange: (String) -> Unit = {},
    serverSettingsLoaded: Boolean = true,
    holdNewBooksForReview: Boolean = false,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit = {},
    pushNotificationsEnabled: Boolean = true,
    onPushNotificationsEnabledChange: (Boolean) -> Unit = {},
    ratingSources: List<RatingSourceStatus> = emptyList(),
    onRatingSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit = { _, _ -> },
    metadataRegion: String = MetadataLocale.DEFAULT.region,
    onMetadataRegionChange: (String) -> Unit = {},
    hardcoverSource: HardcoverSourceStatus? = null,
    hardcoverTokenSave: HardcoverTokenSave = HardcoverTokenSave.Idle,
    hardcoverActions: HardcoverSourceActions = HardcoverSourceActions(),
    isDirty: Boolean = false,
    onSave: () -> Unit = {},
    settingsError: String? = null,
    onClearSettingsError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberCopyToClipboard()
    val linkCopiedMessage = stringResource(Res.string.admin_link_copied)
    val resetCodeCopiedMessage = stringResource(Res.string.admin_reset_code_copied)

    val userToDeleteState = remember { mutableStateOf<AdminUserInfo?>(null) }
    val inviteToRevokeState = remember { mutableStateOf<InviteInfo?>(null) }
    val userToDenyState = remember { mutableStateOf<AdminUserInfo?>(null) }
    val resetToDenyState = remember { mutableStateOf<PasswordResetRequest?>(null) }

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? AdminUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(settingsError) {
        settingsError?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearSettingsError()
        }
    }

    val requestLeave = rememberUnsavedWorkGuard(dirty = isDirty, onLeave = onBackClick)

    // At a large font the hero slides away as the settings scroll, rather than holding a third of the screen.
    val heroScroll = rememberHeroScrollBehavior()
    ListenUpScaffold(
        modifier = modifier.then(heroScroll?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier),
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.common_administration),
                badgeIcon = ListenUpIcons.Shield,
                onBack = requestLeave,
                overline = serverName,
                scrollBehavior = heroScroll,
                actions = { SaveAction(onClick = onSave, enabled = isDirty) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val current = state) {
            is AdminUiState.Loading -> {
                FullScreenLoadingIndicator()
            }

            is AdminUiState.Ready -> {
                AdminContent(
                    focus = focus,
                    state = current,
                    onRegistrationPolicyChange = { viewModel.setRegistrationPolicy(it) },
                    onApproveUserClick = { viewModel.approveUser(it.id) },
                    onDenyUserClick = { userToDenyState.value = it },
                    onDeleteUserClick = { userToDeleteState.value = it },
                    onUserClick = onUserClick,
                    onCopyInviteClick = { invite ->
                        copyToClipboard(invite.url)
                        scope.launch {
                            snackbarHostState.showSnackbar(linkCopiedMessage)
                        }
                    },
                    onRevokeInviteClick = { inviteToRevokeState.value = it },
                    onApprovePasswordResetClick = { viewModel.decidePasswordReset(it.id, approved = true) },
                    onDenyPasswordResetClick = { resetToDenyState.value = it },
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onImportClick = onImportClick,
                    onUploadBooksClick = onUploadBooksClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    serverSettingsLoaded = serverSettingsLoaded,
                    onRemoteUrlChange = onRemoteUrlChange,
                    holdNewBooksForReview = holdNewBooksForReview,
                    onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    ratingSources = ratingSources,
                    onRatingSourceEnabledChange = onRatingSourceEnabledChange,
                    metadataRegion = metadataRegion,
                    onMetadataRegionChange = onMetadataRegionChange,
                    hardcoverSource = hardcoverSource,
                    hardcoverTokenSave = hardcoverTokenSave,
                    hardcoverActions = hardcoverActions,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    AdminConfirmationDialogs(
        viewModel = viewModel,
        userToDeleteState = userToDeleteState,
        inviteToRevokeState = inviteToRevokeState,
        userToDenyState = userToDenyState,
        resetToDenyState = resetToDenyState,
    )

    // The one-time reset code. It is returned exactly once, by the approval call, and by no
    // other surface — there is no way to retrieve it later. `onDismissRequest` is a no-op:
    // dismissal is only via the explicit "Done" button in the dialog itself, so a stray
    // back-press or outside tap can never lose it before the admin has read/copied it.
    (state as? AdminUiState.Ready)?.resetCodeToConvey?.let { code ->
        PasswordResetCodeDialog(
            code = code,
            recipientName = (state as AdminUiState.Ready).resetCodeRecipientName,
            onCopyClick = {
                copyToClipboard(code)
                scope.launch {
                    snackbarHostState.showSnackbar(resetCodeCopiedMessage)
                }
            },
            onDone = { viewModel.dismissResetCode() },
        )
    }
}

/** Where the Admin screen opens: at the top, or on a section a notification tap is about. */
enum class AdminFocus {
    /** Server settings first, as Admin always opens from the menu. */
    TOP,

    /** The pending registrations, where a "Someone wants to join" request is decided. */
    PENDING_REGISTRATIONS,
}

// AdminContent fans hoisted state + per-row callbacks straight into its layout sections;
// a parameter object would only add an indirection layer that Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
internal fun AdminContent(
    state: AdminUiState.Ready,
    focus: AdminFocus,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onUserClick: (String) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onApprovePasswordResetClick: (PasswordResetRequest) -> Unit,
    onDenyPasswordResetClick: (PasswordResetRequest) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onImportClick: () -> Unit,
    onUploadBooksClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    serverSettingsLoaded: Boolean = true,
    holdNewBooksForReview: Boolean,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    ratingSources: List<RatingSourceStatus>,
    onRatingSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit,
    metadataRegion: String,
    onMetadataRegionChange: (String) -> Unit,
    hardcoverSource: HardcoverSourceStatus?,
    hardcoverTokenSave: HardcoverTokenSave,
    hardcoverActions: HardcoverSourceActions,
    modifier: Modifier = Modifier,
) {
    val isExpanded =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    // The settings-and-users list, in either layout — the one a focused open lands in.
    val listState = rememberLazyListState()
    LandOnFocus(listState = listState, focus = focus, state = state, hasHardcoverSource = hardcoverSource != null)

    if (isExpanded) {
        AdminTwoPaneContent(
            listState = listState,
            state = state,
            onRegistrationPolicyChange = onRegistrationPolicyChange,
            onApproveUserClick = onApproveUserClick,
            onDenyUserClick = onDenyUserClick,
            onDeleteUserClick = onDeleteUserClick,
            onUserClick = onUserClick,
            onCopyInviteClick = onCopyInviteClick,
            onRevokeInviteClick = onRevokeInviteClick,
            onApprovePasswordResetClick = onApprovePasswordResetClick,
            onDenyPasswordResetClick = onDenyPasswordResetClick,
            onInviteClick = onInviteClick,
            onCollectionsClick = onCollectionsClick,
            onCategoriesClick = onCategoriesClick,
            onBackupClick = onBackupClick,
            onImportClick = onImportClick,
            onUploadBooksClick = onUploadBooksClick,
            onInboxClick = onInboxClick,
            onLibrarySettingsClick = onLibrarySettingsClick,
            onOrganizeClick = onOrganizeClick,
            serverName = serverName,
            onServerNameChange = onServerNameChange,
            remoteUrl = remoteUrl,
            serverSettingsLoaded = serverSettingsLoaded,
            onRemoteUrlChange = onRemoteUrlChange,
            holdNewBooksForReview = holdNewBooksForReview,
            onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
            pushNotificationsEnabled = pushNotificationsEnabled,
            onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
            ratingSources = ratingSources,
            onRatingSourceEnabledChange = onRatingSourceEnabledChange,
            metadataRegion = metadataRegion,
            onMetadataRegionChange = onMetadataRegionChange,
            hardcoverSource = hardcoverSource,
            hardcoverTokenSave = hardcoverTokenSave,
            hardcoverActions = hardcoverActions,
            modifier = modifier,
        )
    } else {
        LazyColumn(
            state = listState,
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item(key = AdminItem.SERVER_SETTINGS, contentType = AdminItem.SERVER_SETTINGS) {
                ServerSettingsSection(
                    state = state,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    serverSettingsLoaded = serverSettingsLoaded,
                    onRemoteUrlChange = onRemoteUrlChange,
                    holdNewBooksForReview = holdNewBooksForReview,
                    onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    onRegistrationPolicyChange = onRegistrationPolicyChange,
                )
            }

            item(key = AdminItem.RATING_SOURCES, contentType = AdminItem.RATING_SOURCES) {
                RatingSourcesGroup(
                    sources = ratingSources,
                    onSourceEnabledChange = onRatingSourceEnabledChange,
                )
            }

            item(key = AdminItem.STORE_REGION, contentType = AdminItem.STORE_REGION) {
                StoreRegionGroup(region = metadataRegion, onRegionChange = onMetadataRegionChange)
            }

            hardcoverSource?.let { source ->
                item(key = AdminItem.HARDCOVER, contentType = AdminItem.HARDCOVER) {
                    HardcoverSourceGroup(status = source, tokenSave = hardcoverTokenSave, actions = hardcoverActions)
                }
            }

            usersSection(
                state = state,
                onUserClick = onUserClick,
                onDeleteUserClick = onDeleteUserClick,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
                onApprovePasswordResetClick = onApprovePasswordResetClick,
                onDenyPasswordResetClick = onDenyPasswordResetClick,
                onInviteClick = onInviteClick,
            )

            item(key = AdminItem.MANAGEMENT, contentType = AdminItem.MANAGEMENT) {
                ManagementSection(
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onImportClick = onImportClick,
                    onUploadBooksClick = onUploadBooksClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                )
            }
        }
    }
}

// Two-pane expanded layout: Server settings + Users on the left, Management on the right.
@Suppress("LongParameterList")
@Composable
private fun AdminTwoPaneContent(
    listState: LazyListState,
    state: AdminUiState.Ready,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onUserClick: (String) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onApprovePasswordResetClick: (PasswordResetRequest) -> Unit,
    onDenyPasswordResetClick: (PasswordResetRequest) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onImportClick: () -> Unit,
    onUploadBooksClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    serverSettingsLoaded: Boolean,
    holdNewBooksForReview: Boolean,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    ratingSources: List<RatingSourceStatus>,
    onRatingSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit,
    metadataRegion: String,
    onMetadataRegionChange: (String) -> Unit,
    hardcoverSource: HardcoverSourceStatus?,
    hardcoverTokenSave: HardcoverTokenSave,
    hardcoverActions: HardcoverSourceActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1.1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item(key = AdminItem.SERVER_SETTINGS, contentType = AdminItem.SERVER_SETTINGS) {
                ServerSettingsSection(
                    state = state,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    serverSettingsLoaded = serverSettingsLoaded,
                    onRemoteUrlChange = onRemoteUrlChange,
                    holdNewBooksForReview = holdNewBooksForReview,
                    onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    onRegistrationPolicyChange = onRegistrationPolicyChange,
                )
            }

            item(key = AdminItem.RATING_SOURCES, contentType = AdminItem.RATING_SOURCES) {
                RatingSourcesGroup(
                    sources = ratingSources,
                    onSourceEnabledChange = onRatingSourceEnabledChange,
                )
            }

            item(key = AdminItem.STORE_REGION, contentType = AdminItem.STORE_REGION) {
                StoreRegionGroup(region = metadataRegion, onRegionChange = onMetadataRegionChange)
            }

            hardcoverSource?.let { source ->
                item(key = AdminItem.HARDCOVER, contentType = AdminItem.HARDCOVER) {
                    HardcoverSourceGroup(status = source, tokenSave = hardcoverTokenSave, actions = hardcoverActions)
                }
            }

            usersSection(
                state = state,
                onUserClick = onUserClick,
                onDeleteUserClick = onDeleteUserClick,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
                onApprovePasswordResetClick = onApprovePasswordResetClick,
                onDenyPasswordResetClick = onDenyPasswordResetClick,
                onInviteClick = onInviteClick,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item(key = AdminItem.MANAGEMENT, contentType = AdminItem.MANAGEMENT) {
                ManagementSection(
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onImportClick = onImportClick,
                    onUploadBooksClick = onUploadBooksClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Users section (users table + pending registrations + pending invites)
// ---------------------------------------------------------------------------

/** The keys of Admin's settings-and-users list, which keep a scroll position when a group arrives late. */
private object AdminItem {
    const val SERVER_SETTINGS = "server-settings"
    const val RATING_SOURCES = "rating-sources"
    const val STORE_REGION = "store-region"
    const val HARDCOVER = "hardcover"
    const val USERS = "users"
    const val PENDING_REGISTRATIONS = "pending-registrations"
    const val PENDING_INVITES = "pending-invites"
    const val PASSWORD_RESETS = "password-resets"
    const val MANAGEMENT = "management"
}

/** The settings groups always above Users: server settings, rating sources and the store region. */
private const val SETTINGS_GROUPS = 3

/**
 * Scrolls [listState] once to the section [focus] names. The pending registrations sit after the
 * settings groups, Hardcover's when it is shown, and Users; a group that arrives after the landing
 * keeps its place by key. Stays at the top when the registration policy hides the section.
 */
@Composable
private fun LandOnFocus(
    listState: LazyListState,
    focus: AdminFocus,
    state: AdminUiState.Ready,
    hasHardcoverSource: Boolean,
) {
    var landed by rememberSaveable { mutableStateOf(false) }
    val showsPending = state.registrationPolicy == RegistrationPolicy.APPROVAL_QUEUE
    LaunchedEffect(focus, showsPending) {
        if (landed || focus != AdminFocus.PENDING_REGISTRATIONS || !showsPending) return@LaunchedEffect
        val hardcover = if (hasHardcoverSource) 1 else 0
        listState.scrollToItem(SETTINGS_GROUPS + hardcover + 1)
        landed = true
    }
}

private fun LazyListScope.usersSection(
    state: AdminUiState.Ready,
    onUserClick: (String) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onApprovePasswordResetClick: (PasswordResetRequest) -> Unit,
    onDenyPasswordResetClick: (PasswordResetRequest) -> Unit,
    onInviteClick: () -> Unit,
) {
    item(key = AdminItem.USERS, contentType = AdminItem.USERS) {
        UsersGroup(
            state = state,
            onUserClick = onUserClick,
            onDeleteUserClick = onDeleteUserClick,
            onInviteClick = onInviteClick,
        )
    }

    if (state.registrationPolicy == RegistrationPolicy.APPROVAL_QUEUE) {
        item(key = AdminItem.PENDING_REGISTRATIONS, contentType = AdminItem.PENDING_REGISTRATIONS) {
            PendingRegistrationsGroup(
                state = state,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
            )
        }
    }

    if (state.pendingInvites.isNotEmpty()) {
        item(key = AdminItem.PENDING_INVITES, contentType = AdminItem.PENDING_INVITES) {
            PendingInvitesGroup(
                state = state,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
            )
        }
    }

    // Task 15 (password-reset arc): minimal and deliberately temporary — a follow-up plan
    // relocates this into a rewritten people-management surface. Always visible (not gated
    // on emptiness like PendingInvitesGroup) so an admin knows where to look even when the
    // queue is empty.
    item(key = AdminItem.PASSWORD_RESETS, contentType = AdminItem.PASSWORD_RESETS) {
        PasswordResetsGroup(
            state = state,
            onApproveClick = onApprovePasswordResetClick,
            onDenyClick = onDenyPasswordResetClick,
        )
    }
}

// ---------------------------------------------------------------------------
// Password resets section (Task 15 — minimal and deliberately temporary; see
// docs/superpowers/plans/2026-08-09-password-reset.md for the follow-up plan that relocates
// this into a rewritten people-management surface)
// ---------------------------------------------------------------------------

@Composable
private fun PasswordResetsGroup(
    state: AdminUiState.Ready,
    onApproveClick: (PasswordResetRequest) -> Unit,
    onDenyClick: (PasswordResetRequest) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_password_resets),
    ) {
        if (state.pendingPasswordResets.isEmpty()) {
            SectionSegment {
                Text(
                    text = stringResource(Res.string.admin_no_pending_password_resets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        } else {
            state.pendingPasswordResets.forEach { request ->
                PasswordResetRow(
                    request = request,
                    isDeciding = state.decidingPasswordResetId == request.id,
                    // One decision at a time: an approval's code is returned once, and a second
                    // approval would replace it before the admin has seen it.
                    isLocked = state.decidingPasswordResetId != null,
                    onApproveClick = { onApproveClick(request) },
                    onDenyClick = { onDenyClick(request) },
                )
            }
        }
    }
}
