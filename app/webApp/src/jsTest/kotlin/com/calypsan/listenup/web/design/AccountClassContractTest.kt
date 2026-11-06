package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.hardcover.KeepOffRemoves
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.profile.AvatarChange
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverRowState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.features.bulkedit.editing
import com.calypsan.listenup.web.features.devices.DevicesPage
import com.calypsan.listenup.web.features.hardcover.BookHardcoverPanel
import com.calypsan.listenup.web.features.hardcover.HardcoverMatchPage
import com.calypsan.listenup.web.features.hardcover.HardcoverPage
import com.calypsan.listenup.web.features.hardcover.KeptOffBooksPage
import com.calypsan.listenup.web.features.match.failed
import com.calypsan.listenup.web.features.match.photo
import com.calypsan.listenup.web.features.notifications.NotificationPrefsPage
import com.calypsan.listenup.web.features.notifications.NotificationsPage
import com.calypsan.listenup.web.features.notifications.notification
import com.calypsan.listenup.web.features.notifications.pref
import com.calypsan.listenup.web.features.profile.EditProfilePage
import com.calypsan.listenup.web.features.profile.ProfilePage
import com.calypsan.listenup.web.features.profile.editing
import com.calypsan.listenup.web.features.profile.readyProfile
import com.calypsan.listenup.web.features.readers.reader
import com.calypsan.listenup.web.features.settings.SettingsPage
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import io.kotest.core.spec.style.FunSpec

/**
 * Every class Settings, profile, notifications, devices and Hardcover render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class AccountClassContractTest :
    FunSpec({
        test("every class Settings, profile, notifications, devices and Hardcover render is defined in the design sheet") {
            assertClassesDefined(accountContractShapes())
        }
    })

private fun accountContractShapes(): Map<String, @Composable () -> Unit> =
    accountShapes1() +
        notificationShapes() +
        notificationPrefShapes() +
        profileShapes() +
        editProfileShapes() +
        devicesShapes() +
        hardcoverShapes()

private fun accountShapes1(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#1: SettingsPage(SettingsUiState(isLoading = true), {},") {
            // Loading and loaded: the skeleton's class lives only in the former.
            SettingsPage(SettingsUiState(isLoading = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        },
        shape("#2: SettingsPage( SettingsUiState( isLoading = false, se") {
            SettingsPage(
                SettingsUiState(
                    isLoading = false,
                    serverUrl = "https://listenup.example",
                    serverVersion = "0.9.1",
                ),
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                hardcoverRow = HardcoverRowState.Connected("simon"),
            )
        },
    )

/**
 * The preference rows in every state, including the two only a particular server produces: a
 * push-ineligible type (whose switch is disabled) and an all-unknown list (whose empty copy is the
 * only thing that renders).
 */
private fun notificationPrefShapes(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("notificationPref #1: { NotificationPrefsPage( state = Notific") {
            NotificationPrefsPage(
                state =
                    NotificationPrefsUiState.Data(
                        listOf(
                            pref(type = "campfire_invite"),
                            pref(type = "registration_decision", pushEligible = false),
                        ),
                    ),
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
        shape("notificationPref #2: { NotificationPrefsPage( state = Notific") {
            NotificationPrefsPage(
                state = NotificationPrefsUiState.Data(listOf(pref(type = "some_future_type"))),
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
        shape("notificationPref #3: { NotificationPrefsPage( state = Notific") {
            NotificationPrefsPage(
                state = NotificationPrefsUiState.Error(InternalError()),
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
        shape("notificationPref #4: { NotificationPrefsPage( state = Notific") {
            NotificationPrefsPage(
                state = NotificationPrefsUiState.Loading,
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
    )

private fun profileShapes(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("profile #1: { ProfilePage(readyProfile(), onOpenBook") {
            ProfilePage(readyProfile(), onOpenBook = {}, onOpenShelf = {}, onRetry = {}, onEditProfile = {})
        },
        // Own profile, for `.prof-edit` — it renders nowhere else.
        shape("profile #2: { ProfilePage(readyProfile(isOwnProfile") {
            ProfilePage(readyProfile(isOwnProfile = true), {}, {}, {}, {})
        },
        shape("profile #3: { ProfilePage( state = readyProfile(rece") {
            ProfilePage(
                state = readyProfile(recentBooks = emptyList(), publicShelves = emptyList()),
                onOpenBook = {},
                onOpenShelf = {},
                onRetry = {},
                onEditProfile = {},
            )
        },
        shape("profile #4: { ProfilePage(UserProfileUiState.Error(N") {
            ProfilePage(UserProfileUiState.Error("No such listener."), {}, {}, {}, {})
        },
        shape("profile #5: { ProfilePage(UserProfileUiState.Loading") {
            ProfilePage(UserProfileUiState.Loading, {}, {}, {}, {})
        },
    )

/**
 * Edit Profile in every shape that draws a class of its own.
 *
 * The photo row alone has three: the saved avatar, a staged upload previewing from its own bytes,
 * and a staged removal previewing as the monogram. A contract that only rendered the happy path
 * would leave two of the three unchecked, and they are exactly the ones a reader sees mid-edit.
 */
private fun editProfileShapes(): Map<String, @Composable () -> Unit> {
    fun form(
        state: EditProfileUiState,
        saveError: String? = null,
    ): @Composable () -> Unit =
        {
            EditProfilePage(state, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, saveError)
        }

    return shapes(
        // A dirty form with a photo to remove and a failed save on it — most of the classes.
        "editProfile #1: form(editing(tagline = Counting on it.," to
            form(editing(tagline = "Counting on it.", hasImageAvatar = true, isDirty = true), saveError = "Nope."),
        "editProfile #2: form(editing(avatarChange = AvatarChange" to
            form(editing(avatarChange = AvatarChange.Upload(byteArrayOf(1), "image/png"), hasImageAvatar = true)),
        "editProfile #3: form(editing(avatarChange = AvatarChange" to
            form(editing(avatarChange = AvatarChange.RevertToAuto, hasImageAvatar = true)),
        "editProfile #4: form(EditProfileUiState.Error(No user da" to form(EditProfileUiState.Error("No user data available")),
        "editProfile #5: form(EditProfileUiState.Loading)" to form(EditProfileUiState.Loading),
    )
}

/**
 * The notification inbox in every state it has, plus the sidebar wearing a badge.
 *
 * Lifted out of the test body for the same reason [discoverShapes] was: this one contract renders
 * every page in the app, and the class had grown past the size the build allows. Grouping by
 * surface keeps each block readable and the class inside its budget.
 */
private fun notificationShapes(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("notification #1: { NotificationsPage( state = Notificatio") {
            // The inbox in all three states, plus a read row — the dot's "read"
            // form is a class of its own and would otherwise never be rendered here.
            NotificationsPage(
                state =
                    NotificationsUiState.Data(
                        listOf(
                            notification(id = "n1"),
                            notification(id = "n2", readAt = 1L),
                        ),
                    ),
                nowMs = 1_800_000_000_000L,
                onOpen = {},
            )
            NotificationsPage(state = NotificationsUiState.Empty, nowMs = 0L, onOpen = {})
            NotificationsPage(state = NotificationsUiState.Loading, nowMs = 0L, onOpen = {})
            // The sidebar with a badge on it. `Shell` above renders without one, so
            // `.nav-badge` joins the contract from here.
            Shell(
                sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home)))),
                active = "home",
                footer = listOf(NavEntry("notifications", "Notifications", WebIcon.Bell, badge = 3)),
                onToggleCollapse = {},
            ) {}
        },
    )

/**
 * Every shape the Devices screen can be in, plus the dialog it opens.
 *
 * The dialog is rendered here rather than only in its own spec because its classes belong to the
 * sheet like any other — and it is the one component that renders outside the mount's subtree, in
 * the browser's top layer, which is exactly the kind of thing a contract stops going unstyled.
 */
private fun devicesShapes(): Map<String, @Composable () -> Unit> {
    fun device(
        id: String,
        name: String,
        isCurrent: Boolean = false,
    ) = DeviceRow(
        sessionId = id,
        displayName = name,
        secondary = "iOS 17.2 · ListenUp 1.0.0",
        lastUsedAt = 0L,
        isCurrent = isCurrent,
    )

    fun page(state: DevicesUiState): @Composable () -> Unit = { DevicesPage(state, 0L, {}, {}, {}) }

    return shapes(
        "devices #1: page(DevicesUiState.Loading)" to page(DevicesUiState.Loading),
        "devices #2: page(DevicesUiState.Error(InternalError(" to page(DevicesUiState.Error(InternalError(debugInfo = "nope"))),
        // No other devices: the empty line has its own class.
        "devices #3: page(DevicesUiState.Ready(listOf(device(" to
            page(DevicesUiState.Ready(listOf(device("s1", "This Mac", isCurrent = true)))),
        "devices #4: page( DevicesUiState.Ready( devices = li" to
            page(
                DevicesUiState.Ready(
                    devices = listOf(device("s1", "This Mac", isCurrent = true), device("s2", "Simon's iPhone")),
                    signingOut = setOf("s2"),
                ),
            ),
        shape("devices #5: { ConfirmDialog( open = true, title = Si") {
            ConfirmDialog(
                open = true,
                title = "Sign out all other devices?",
                body = "Every other device is signed out. This one stays signed in.",
                confirmLabel = "Sign out all other devices",
                onConfirm = {},
                onDismiss = {},
            )
        },
    )
}

/**
 * The Hardcover page in every phase, the failure note included — each phase draws its own layout,
 * so a phase nobody renders here is a phase whose classes nothing checks.
 */
private fun hardcoverShapes(): Map<String, @Composable () -> Unit> = hardcoverSettingsShapes() + hardcoverMatchShapes()

/** Find on Hardcover in each search shape, and Book Detail's panel in each of its own. */
private fun hardcoverMatchShapes(): Map<String, @Composable () -> Unit> {
    val strong = HardcoverCandidateRow(1L, 9L, "Project Hail Mary", listOf("Andy Weir"), 2021, 812, true, true)
    val quiet = HardcoverCandidateRow(2L, null, "A Summary", emptyList(), null, 0, false, false)
    val match = HardcoverMatchedBook(1L, "Project Hail Mary", listOf("Andy Weir"), 2021, true, 9L)

    fun find(search: HardcoverSearchState): @Composable () -> Unit =
        {
            HardcoverMatchPage(
                HardcoverMatchUiState.Ready(
                    "b1",
                    "Project Hail Mary",
                    "Andy Weir",
                    null,
                    null,
                    "q",
                    search,
                    match,
                    null,
                    false,
                    listOf("x"),
                ),
                {},
                {},
                {},
                {},
                {},
                {},
                {},
            )
        }
    return shapes(
        "hardcoverMatch #1: find(HardcoverSearchState.Results(listOf" to find(HardcoverSearchState.Results(listOf(strong, quiet))),
        "hardcoverMatch #2: find(HardcoverSearchState.Results(listOf" to find(HardcoverSearchState.Results(listOf(quiet))),
        "hardcoverMatch #3: find(HardcoverSearchState.NoResults)" to find(HardcoverSearchState.NoResults),
        "hardcoverMatch #4: find(HardcoverSearchState.Failed(Hardcov" to find(HardcoverSearchState.Failed(HardcoverError.Unavailable())),
        shape("hardcoverMatch #5: { BookHardcoverPanel(BookHardcoverUiStat") {
            BookHardcoverPanel(BookHardcoverUiState.NeedsMatch, {}, {}, {})
        },
        shape("hardcoverMatch #6: { BookHardcoverPanel(BookHardcoverUiStat") {
            BookHardcoverPanel(BookHardcoverUiState.Linked(match, HardcoverBookSync.REMOVED_ON_HARDCOVER), {}, {}, {})
        },
        shape("hardcoverMatch #7: { BookHardcoverPanel(BookHardcoverUiStat") {
            BookHardcoverPanel(BookHardcoverUiState.Unmatched, {}, {}, {})
        },
        shape("hardcoverMatch #8: { BookHardcoverPanel(BookHardcoverUiStat") {
            BookHardcoverPanel(BookHardcoverUiState.KeptOff(), {}, {}, {})
        },
        shape("hardcoverMatch #9: { BookHardcoverPanel( BookHardcoverUiSta") {
            BookHardcoverPanel(
                BookHardcoverUiState.Linked(match, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.READS),
                {},
                {},
                {},
            )
        },
        shape("hardcoverMatch #10: { KeptOffBooksPage(KeptOffBooksUiState.L") {
            KeptOffBooksPage(KeptOffBooksUiState.Loaded(listOf(KeptOffBook("b1", "Educated", "Tara Westover", null, null))), {}, {}, {})
        },
    )
}

private fun hardcoverSettingsShapes(): Map<String, @Composable () -> Unit> {
    fun page(state: HardcoverSettingsUiState): @Composable () -> Unit =
        {
            HardcoverPage(
                state,
                onConnect = {},
                onDisconnect = {},
                onSyncNow = {},
                onSetShareMode = {},
                onSendHistory = {},
                onDismissHistory = {},
                onFindMatch = {},
                onOpenKeptOff = {},
                onOpenSettings = {},
                nowMs = 0L,
                copyText = { _, done -> done(true) },
            )
        }

    fun connectedWith(history: HardcoverHistory) =
        HardcoverSettingsUiState.Connected(username = "simon", since = 0L, isDisconnecting = false, history = history)
    return shapes(
        "hardcoverSettings #1: page(HardcoverSettingsUiState.Loading)" to page(HardcoverSettingsUiState.Loading),
        "hardcoverSettings #2: page(HardcoverSettingsUiState.NotOffered" to page(HardcoverSettingsUiState.NotOffered),
        "hardcoverSettings #3: page(HardcoverSettingsUiState.NotConnect" to
            page(HardcoverSettingsUiState.NotConnected(lastFailure = HardcoverLinkFailure.DENIED, isStarting = false)),
        "hardcoverSettings #4: page( HardcoverSettingsUiState.Linking(" to
            page(
                HardcoverSettingsUiState.Linking(
                    userCode = "ABCD-1234",
                    verificationUri = "https://hardcover.app/link",
                    verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                    expiresAt = 0L,
                ),
            ),
        "hardcoverSettings #5: page(HardcoverSettingsUiState.Connected(" to
            page(HardcoverSettingsUiState.Connected(username = "simon", since = 0L, isDisconnecting = false)),
        "hardcoverSettings #6: page(HardcoverSettingsUiState.Connected(" to
            page(HardcoverSettingsUiState.Connected(username = "simon", since = 0L, isDisconnecting = false, keptOffBookCount = 3)),
        "hardcoverSettings #7: page(connectedWith(HardcoverHistory.Offe" to page(connectedWith(HardcoverHistory.Offer(74))),
        "hardcoverSettings #8: page(connectedWith(HardcoverHistory.Send" to page(connectedWith(HardcoverHistory.Sending(23, 74))),
        "hardcoverSettings #9: page( HardcoverSettingsUiState.Connected" to
            page(
                HardcoverSettingsUiState.Connected(
                    username = "simon",
                    since = 0L,
                    isDisconnecting = false,
                    history = HardcoverHistory.Done(70, 4),
                    booksToMatch = listOf(HardcoverBookToMatch("b1", "Book", "Author", null, null)),
                    isMatchListKnown = true,
                ),
            ),
        "hardcoverSettings #10: page(connectedWith(HardcoverHistory.Avai" to page(connectedWith(HardcoverHistory.Available(74))),
        "hardcoverSettings #11: page( HardcoverSettingsUiState.Connected" to
            page(
                HardcoverSettingsUiState.Connected(
                    username = "simon",
                    since = 0L,
                    isDisconnecting = false,
                    shareMode = HardcoverShareMode.FINISHED_ONLY,
                    isSavingShareMode = true,
                ),
            ),
        // The sync line in each shape, the stuck card, and Needs a match both listed and empty.
        "hardcoverSettings #12: page( HardcoverSettingsUiState.Connected" to
            page(
                HardcoverSettingsUiState.Connected(
                    username = "simon",
                    since = 0L,
                    isDisconnecting = false,
                    sync = HardcoverSyncStatus.Syncing,
                    booksToMatch = listOf(HardcoverBookToMatch("b1", "Piranesi", "Susanna Clarke", null, null)),
                    isMatchListKnown = true,
                ),
            ),
        "hardcoverSettings #13: page( HardcoverSettingsUiState.Connected" to
            page(
                HardcoverSettingsUiState.Connected(
                    username = "simon",
                    since = 0L,
                    isDisconnecting = false,
                    sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.PUSH_STALLED),
                    isMatchListKnown = true,
                ),
            ),
        "hardcoverSettings #14: page(HardcoverSettingsUiState.Broken(rea" to
            page(HardcoverSettingsUiState.Broken(reason = HardcoverBrokenReason.REVOKED, username = "simon", isStarting = false)),
    )
}
