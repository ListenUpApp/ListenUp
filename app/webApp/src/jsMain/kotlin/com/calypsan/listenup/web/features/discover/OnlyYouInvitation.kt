package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.UserAvatar
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Listening now when you are the only person on the server (spec §3.8): your avatar beside two dashed
 * circles for the people not here yet, what Discover will show once they are, and — for an admin only —
 * **Invite someone**. A member sees no button and no gap where it was.
 *
 * The title is an H3: it sits under the section's own H2.
 *
 * @param viewer Whose avatar leads; null leaves the dashed circles alone rather than draw a blank one.
 * @param onInvite Opens the invite page; null hides the button.
 */
@Composable
internal fun OnlyYouInvitation(
    viewer: DiscoverViewer?,
    onInvite: (() -> Unit)?,
) {
    Div(attrs = { classes("disc-invite") }) {
        Div(attrs = {
            classes("disc-invite-avatars")
            attr("aria-hidden", "true")
        }) {
            viewer?.let { UserAvatar(userId = it.userId, name = it.displayName, size = INVITE_AVATAR) }
            repeat(2) { Span(attrs = { classes("disc-invite-ghost") }) }
        }
        H3(attrs = { classes("disc-invite-title") }) { Text(DiscoverWords.ONLY_YOU_TITLE) }
        P(attrs = { classes("disc-invite-body") }) { Text(DiscoverWords.ONLY_YOU_BODY) }
        onInvite?.let { invite ->
            Button(kind = ButtonKind.Secondary, onClick = invite) { Text(DiscoverWords.INVITE_SOMEONE) }
        }
    }
}

/** The invitation's avatars, in px (the board's 40). */
private const val INVITE_AVATAR = 40
