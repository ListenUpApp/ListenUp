package com.calypsan.listenup.web.features.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.web.features.bookdetail.BookRouteContent
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent

/**
 * The profile family: a listener's page, and — if it is your own — the form over it.
 *
 * Pulled out of [RouteContent] for the same reason `BookRouteContent` was: a page and the editor
 * for that page are one family, and folding both into the routing chain pushed it past the
 * branching the build allows.
 */
@Composable
internal fun ProfileRouteContent(
    userId: String,
    editing: Boolean,
    currentUserId: String?,
    router: Router,
    openProfile: OpenProfile,
    openEditProfile: OpenEditProfile,
) {
    if (editing) {
        EditProfileRoute(
            router = router,
            openEditProfile = openEditProfile,
            userId = userId,
            currentUserId = currentUserId,
        )
    } else {
        ProfileRoute(
            router = router,
            openProfile = openProfile,
            userId = userId,
            onEditProfile = { router.navigate(Routes.profileEdit(userId)) },
        )
    }
}

/**
 * `/profile/{userId}` — a listener's page.
 *
 * Keyed on [userId], for the reason `UserProfileViewModel.loadProfile` makes necessary: it returns
 * early for a repeat of the id it already holds, so a session reused across two different people
 * would keep showing the first one.
 */
@Composable
private fun ProfileRoute(
    router: Router,
    openProfile: OpenProfile,
    userId: String,
    onEditProfile: () -> Unit,
) {
    val session = rememberSession(userId) { openProfile(userId) }

    ProfilePage(
        state = session.state.collectAsState().value,
        onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
        onOpenShelf = { id -> router.navigate(Routes.shelf(id)) },
        onRetry = session.onRetry,
        onEditProfile = onEditProfile,
    )
}

/**
 * `/profile/{userId}/edit` — your own profile, in a form.
 *
 * ⛔ **The id in the URL is not what gets edited.** [com.calypsan.listenup.client.presentation.profile.EditProfileViewModel]
 * reads `observeCurrentUser()`, so it always edits whoever holds the session — which means an
 * unguarded route would put YOUR name, tagline and password fields under SOMEONE ELSE'S URL, and a
 * save from that page would look like it had edited them. So a [userId] that is not [currentUserId]
 * is sent to that person's read-only page instead, which is the thing the URL actually named.
 *
 * A null [currentUserId] is "not known yet", not "not you": the flow behind it answers a moment
 * after mount, and redirecting on it would bounce every reader off their own form on arrival.
 *
 * `saveError` is held here rather than in the page because it arrives as a one-shot event. It is
 * cleared on the next Save rather than on the next keystroke — the reader needs it still on screen
 * while they fix the field it is about.
 */
@Composable
private fun EditProfileRoute(
    router: Router,
    openEditProfile: OpenEditProfile,
    userId: String,
    currentUserId: String?,
) {
    if (currentUserId != null && currentUserId != userId) {
        LaunchedEffect(userId) { router.replace(Routes.profile(userId)) }
        return
    }

    val session = rememberSession { openEditProfile() }

    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is EditProfileEvent.SaveFailed -> saveError = event.message

                // The refreshed profile is the confirmation, exactly as on Android — a notice here
                // would be read on a page the reader has already left.
                EditProfileEvent.SaveSucceeded -> router.navigate(Routes.profile(userId), force = true)
            }
        }
    }

    EditProfilePage(
        state = session.state.collectAsState().value,
        onFirstName = session.onFirstName,
        onLastName = session.onLastName,
        onTagline = session.onTagline,
        onCurrentPassword = session.onCurrentPassword,
        onNewPassword = session.onNewPassword,
        onConfirmPassword = session.onConfirmPassword,
        onPickAvatar = session.onPickAvatar,
        onRemoveAvatar = session.onRemoveAvatar,
        onSave = {
            saveError = null
            session.onSave()
        },
        // The page has already asked about a draft: do not ask twice.
        onCancel = { router.navigate(Routes.profile(userId), force = true) },
        saveError = saveError,
    )
}
