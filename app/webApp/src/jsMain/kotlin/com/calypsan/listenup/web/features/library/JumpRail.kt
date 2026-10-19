package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.web.design.VirtualJump
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Text

/** One letter of the rail; [hasSection] false draws it, but it can't be pressed. */
internal data class RailLetter(
    val letter: String,
    val hasSection: Boolean,
)

/**
 * The rail's letters: the whole A–Z then `#` (board `Library-WebDesktop`), so its shape never changes
 * with the library, each marked by whether the list has a section under it. A letter outside A–Z
 * that the list does use sits before `#`. A descending sort runs the letters Z to A, as the list
 * does. Fewer than two letters in the list offers nothing to jump between, so no rail.
 */
internal fun railLetters(sections: List<LetterSection>): List<RailLetter> {
    val present = sections.map { it.letter }.distinct()
    if (present.size < 2) return emptyList()
    val alphabet = ('A'..'Z').map { it.toString() }
    val alphabetic = present.filter { it in alphabet }
    val descending = alphabetic.size > 1 && alphabetic.first() > alphabetic.last()
    val letters =
        (if (descending) alphabet.reversed() else alphabet) +
            present.filter { it !in alphabet && it != NUMERIC } +
            NUMERIC
    return letters.map { RailLetter(it, it in present) }
}

/**
 * The Library's A–Z jump rail (spec §5.2, board `Library-WebDesktop`): real buttons, each named
 * "Jump to S", small glyphs with 44px hit areas, in a landmark named "Jump to letter". A letter with
 * no section is shown but disabled; [current] (the section at the top of the list) is marked.
 */
@Composable
internal fun JumpRail(
    letters: List<RailLetter>,
    current: String?,
    onJump: (String) -> Unit,
) {
    if (letters.isEmpty()) return
    Nav(attrs = {
        classes("lib-rail")
        attr("aria-label", "Jump to letter")
    }) {
        letters.forEach { (letter, hasSection) ->
            Button(attrs = {
                attr("type", "button")
                attr("aria-label", "Jump to $letter")
                if (!hasSection) attr("disabled", "")
                if (hasSection && letter == current) {
                    classes("is-current")
                    attr("aria-current", "true")
                }
                onClick { onJump(letter) }
            }) { Text(letter) }
        }
    }
}

/**
 * A lettered Library list with its jump rail beside it: [content] draws the list, wired to the
 * [VirtualJump] it is handed, and the rail reads the same shared [letters]. Books, Series, Authors
 * and Narrators all go through here, so every name-sorted page indexes the same way.
 */
@Composable
internal fun WithJumpRail(
    letters: List<LetterSection>,
    content: @Composable (VirtualJump) -> Unit,
) {
    val jump = remember { VirtualJump() }
    val rail = remember(letters) { railLetters(letters) }
    Div(attrs = {
        classes("lib-books")
        if (rail.isNotEmpty()) classes("has-rail")
    }) {
        content(jump)
        JumpRail(rail, current = jump.current?.toString()) { jump.toSection(it.first()) }
    }
}

/** Where a name that starts with no letter files. */
private const val NUMERIC = "#"
