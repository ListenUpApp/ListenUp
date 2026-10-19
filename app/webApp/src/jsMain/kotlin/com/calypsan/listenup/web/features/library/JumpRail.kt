package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.library.LetterSection
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Text

/** Each letter once, in list order: a recurring letter jumps to its first run (see [LetterSection]). */
internal fun railLetters(sections: List<LetterSection>): List<String> = sections.map { it.letter }.distinct()

/**
 * The Books A–Z jump rail (spec §5.2, board `Library-WebDesktop`): real buttons, each named
 * "Jump to S", small glyphs with 44px hit areas, in a landmark named "Jump to letter". A single
 * letter offers nothing to jump between, so it draws no rail.
 */
@Composable
internal fun JumpRail(
    letters: List<String>,
    onJump: (String) -> Unit,
) {
    if (letters.size < 2) return
    Nav(attrs = {
        classes("lib-rail")
        attr("aria-label", "Jump to letter")
    }) {
        letters.forEach { letter ->
            Button(attrs = {
                attr("type", "button")
                attr("aria-label", "Jump to $letter")
                onClick { onJump(letter) }
            }) { Text(letter) }
        }
    }
}
