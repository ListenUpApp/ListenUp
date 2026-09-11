package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.SwitchField
import com.calypsan.listenup.web.design.UserAvatar
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * One member, and what they are allowed to do.
 *
 * ⛔ The permission switches are optimistic — the ViewModel flips them locally, saves, then
 * reconciles against what the server actually stored. So the switch answering instantly is
 * correct, and a switch that snaps back a moment later is the server disagreeing, not a glitch.
 */
@Composable
fun UserDetailPage(
    state: UserDetailUiState,
    onToggleCanEdit: () -> Unit,
    onToggleCanShare: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("usr") }) {
        Breadcrumb(trail = listOf("People", userCrumb(state)), onNavigate = { onOpenAdmin() })

        when (state) {
            UserDetailUiState.Loading -> Div(attrs = { classes("skel", "usr-skel") })
            is UserDetailUiState.Error -> P(attrs = { classes("usr-none") }) { Text(state.error.message) }
            is UserDetailUiState.Ready -> ReadyUser(state, onToggleCanEdit, onToggleCanShare)
        }
    }
}

@Composable
private fun ReadyUser(
    state: UserDetailUiState.Ready,
    onToggleCanEdit: () -> Unit,
    onToggleCanShare: () -> Unit,
) {
    val user = state.user
    Div(attrs = { classes("usr-head") }) {
        UserAvatar(userId = user.id, name = user.displayableName, size = AVATAR_SIZE)
        Div(attrs = { classes("usr-who") }) {
            H1(attrs = { classes("usr-t") }) { Text(user.displayableName) }
            Span(attrs = { classes("usr-e") }) { Text(user.email) }
        }
    }

    Panel(title = "Details") {
        MetaList(
            buildList {
                add(MetaEntry("Role", roleLabel(user)))
                add(MetaEntry("Status", user.status.replaceFirstChar { it.uppercase() }))
            },
        )
    }

    Panel(title = "Permissions") {
        // ⛔ The owner's permissions are not merely disabled here — the server refuses to change
        // them, and a switch that looks live and then reverts is worse than one that never moved.
        // The sentence says why, so "why can't I change this?" is answered on the page.
        if (state.isProtected) {
            P(attrs = { classes("usr-note") }) {
                Text("This is the server's owner. Their permissions can't be changed from here.")
            }
        }
        // One enablement for both switches: they are the same permission round-trip, and the two
        // reasons a switch is held still — the server will refuse it, or a save is already in
        // flight — apply to each of them identically.
        val live = !state.isProtected && !state.isSaving
        SwitchField(
            label = "Can edit book details",
            checked = state.canEdit,
            onChange = { onToggleCanEdit() },
            enabled = live,
        )
        SwitchField(
            label = "Can share with others",
            checked = state.canShare,
            onChange = { onToggleCanShare() },
            enabled = live,
        )
        state.error?.let { failure -> P(attrs = { classes("usr-err") }) { Text(failure.message) } }
    }
}

/** The trail's last crumb: the member's name once it is known, and the page's own name until then. */
internal fun userCrumb(state: UserDetailUiState): String =
    if (state is UserDetailUiState.Ready) state.user.displayableName else "Member"

/** What this member is called on the server, in the words the People page uses. */
internal fun roleLabel(user: AdminUserInfo): String =
    when {
        user.isRoot -> "Owner"
        user.role.equals("admin", ignoreCase = true) -> "Admin"
        else -> "Member"
    }

private const val AVATAR_SIZE = 56
