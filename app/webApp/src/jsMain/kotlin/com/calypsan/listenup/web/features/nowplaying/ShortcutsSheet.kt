package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Dd
import com.calypsan.listenup.web.design.Dl
import com.calypsan.listenup.web.design.Dt
import com.calypsan.listenup.web.design.Kbd
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The keyboard shortcuts (spec §5.8, `Shortcuts-WebDesktop`), from `?`, the bar's Shortcuts button and More: one row per
 * [PLAYER_SHORTCUTS] entry, read as pairs (a `<dl>`: the keys, then what they do). A [PlayerPanel], so it hangs from
 * its opener on wide and is a bottom sheet on a phone, and Esc or Close hand focus back.
 */
@Composable
internal fun ShortcutsSheet(
    open: Boolean,
    placement: PanelPlacement,
    onDismiss: () -> Unit,
) {
    PlayerPanel(
        open = open,
        title = NowPlayingWords.KEYBOARD_SHORTCUTS,
        panelClass = "keys-dlg",
        placement = placement,
        onDismiss = onDismiss,
    ) {
        Dl(attrs = { classes("keys-list") }) {
            PLAYER_SHORTCUTS.forEach { shortcut ->
                Dt(attrs = { classes("keys-k") }) {
                    shortcut.keys.forEachIndexed { index, key ->
                        if (index > 0) Span(attrs = { classes("keys-or") }) { Text(" ") }
                        Kbd { Text(key) }
                    }
                }
                Dd(attrs = { classes("keys-d") }) { Text(shortcut.description) }
            }
        }
    }
}
