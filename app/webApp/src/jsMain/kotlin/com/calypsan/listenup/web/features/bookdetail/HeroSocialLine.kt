package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.focusAsLanding
import com.calypsan.listenup.web.features.readers.READERS_PANEL_ID
import com.calypsan.listenup.web.motion.prefersLessMotion
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * The hero's social line (spec §2.10): up to three overlapping avatars and one sentence, as one in-page link to the
 * Readers panel. The avatars are hidden from assistive tech; the sentence names the readers. Nothing when nobody but
 * you has read the book.
 */
@Composable
internal fun HeroSocialLine(summary: ReadersSummary) {
    if (summary.avatars.isEmpty()) return
    A(href = "#$READERS_PANEL_ID", attrs = {
        classes("bd-social")
        onClick { event ->
            event.preventDefault()
            val link = event.currentTarget as? HTMLElement ?: return@onClick
            val panel = link.ownerDocument?.getElementById(READERS_PANEL_ID) as? HTMLElement ?: return@onClick
            panel.scrollIntoView(scrollToStart())
            // Focus follows the eye, onto the heading the link names; it stays out of the Tab order.
            (panel.querySelector("h2") as? HTMLElement ?: panel).let(::focusAsLanding)
        }
    }) {
        Span(attrs = {
            classes("bd-social-avatars")
            attr("aria-hidden", "true")
        }) {
            summary.avatars.take(SOCIAL_AVATARS).forEach {
                UserAvatar(userId = it.userId, name = it.displayName, size = AVATAR_SIZE)
            }
        }
        Span(attrs = { classes("bd-social-text") }) { SocialSentence(summary) }
    }
}

/** The sentence, with the names in bold — the words [socialSentence] spells. */
@Composable
private fun SocialSentence(summary: ReadersSummary) {
    val sentence = socialSentence(summary)
    val named =
        (
            summary.listening.take(
                NAMED_PER_CLAUSE,
            ) + summary.finished.take(NAMED_PER_CLAUSE)
        ).map { it.firstName }
    var from = 0
    named.forEach { name ->
        val at = sentence.indexOf(name, startIndex = from)
        if (at >= 0) {
            if (at > from) Text(sentence.substring(from, at))
            B { Text(name) }
            from = at + name.length
        }
    }
    if (from < sentence.length) Text(sentence.substring(from))
}

/**
 * "Maya and Priya are listening · Tom finished it": two names a clause, each clause counting its own overflow
 * ("and 3 others"). Inline English: web has no string catalog.
 */
internal fun socialSentence(summary: ReadersSummary): String {
    val listening = clause(summary.listening, one = "is listening", many = "are listening")
    val finished = clause(summary.finished, one = "finished it", many = "finished it")
    return listOfNotNull(listening, finished).joinToString(" · ")
}

private fun clause(
    readers: List<ReaderRef>,
    one: String,
    many: String,
): String? {
    val names = readers.map { it.firstName }
    return when (names.size) {
        0 -> null
        1 -> "${names[0]} $one"
        2 -> "${names[0]} and ${names[1]} $many"
        3 -> "${names[0]}, ${names[1]} and 1 other $many"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} others $many"
    }
}

/**
 * `scrollIntoView` options: to the top of the panel, smoothly unless the reader asked for less motion.
 */
private fun scrollToStart(): dynamic {
    val options = js("{}")
    options.block = "start"
    options.behavior = if (prefersLessMotion()) "auto" else "smooth"
    return options
}

private const val SOCIAL_AVATARS = 3

private const val NAMED_PER_CLAUSE = 2

private const val AVATAR_SIZE = 28
