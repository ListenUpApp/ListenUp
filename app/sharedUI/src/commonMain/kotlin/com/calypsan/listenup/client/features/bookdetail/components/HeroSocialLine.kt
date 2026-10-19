package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_social_finished_many
import listenup.composeapp.generated.resources.book_detail_social_finished_many_one
import listenup.composeapp.generated.resources.book_detail_social_finished_one
import listenup.composeapp.generated.resources.book_detail_social_finished_two
import listenup.composeapp.generated.resources.book_detail_social_hint
import listenup.composeapp.generated.resources.book_detail_social_join
import listenup.composeapp.generated.resources.book_detail_social_listening_many
import listenup.composeapp.generated.resources.book_detail_social_listening_many_one
import listenup.composeapp.generated.resources.book_detail_social_listening_one
import listenup.composeapp.generated.resources.book_detail_social_listening_two
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How many avatars the line draws. */
private const val SOCIAL_AVATARS = 3

/** The four wordings of one clause: one name, two, two and one other, two and N others. */
private data class ClauseKeys(
    val one: StringResource,
    val two: StringResource,
    val manyOne: StringResource,
    val many: StringResource,
)

private val ListeningKeys =
    ClauseKeys(
        one = Res.string.book_detail_social_listening_one,
        two = Res.string.book_detail_social_listening_two,
        manyOne = Res.string.book_detail_social_listening_many_one,
        many = Res.string.book_detail_social_listening_many,
    )

private val FinishedKeys =
    ClauseKeys(
        one = Res.string.book_detail_social_finished_one,
        two = Res.string.book_detail_social_finished_two,
        manyOne = Res.string.book_detail_social_finished_many_one,
        many = Res.string.book_detail_social_finished_many,
    )

/** One clause, or null when nobody is in it. Each clause counts its own overflow from its own list. */
@Composable
private fun clause(
    readers: List<ReaderRef>,
    keys: ClauseKeys,
): String? {
    val names = readers.map { it.firstName }
    return when (names.size) {
        0 -> null
        1 -> stringResource(keys.one, names[0])
        2 -> stringResource(keys.two, names[0], names[1])
        3 -> stringResource(keys.manyOne, names[0], names[1])
        else -> stringResource(keys.many, names[0], names[1], names.size - 2)
    }
}

/**
 * The hero's social sentence (spec §2.10): up to two names a clause, listening first ("Maya and Priya are listening ·
 * Tom finished it"), with the names in Medium weight. Built from catalog keys, never from ViewModel copy.
 */
@Composable
internal fun socialSentence(summary: ReadersSummary): AnnotatedString {
    val listening = clause(summary.listening, ListeningKeys)
    val finished = clause(summary.finished, FinishedKeys)
    val plain =
        if (listening != null && finished != null) {
            stringResource(Res.string.book_detail_social_join, listening, finished)
        } else {
            listening ?: finished.orEmpty()
        }
    val named = (summary.listening.take(2) + summary.finished.take(2)).map { it.firstName }
    val nameStyle = SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    return buildAnnotatedString {
        append(plain)
        var from = 0
        named.forEach { name ->
            val at = plain.indexOf(name, startIndex = from)
            if (at >= 0) {
                addStyle(nameStyle, at, at + name.length)
                from = at + name.length
            }
        }
    }
}

/**
 * Up to three overlapping avatars and the sentence, as one link to the Readers section (spec §2.10, §5.2): read as the
 * sentence, avatars hidden. Renders nothing when nobody but you has read the book.
 *
 * @param ringColor The ring around each avatar: the surface the line sits on, so the overlap reads as a cut.
 */
@Composable
internal fun HeroSocialLine(
    summary: ReadersSummary,
    ringColor: Color,
    onOpenReaders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.avatars.isEmpty()) return
    val haptics = LocalHaptics.current
    val sentence = socialSentence(summary)
    val hint = stringResource(Res.string.book_detail_social_hint)
    val open = {
        haptics.press()
        onOpenReaders()
    }
    Row(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = hint, onClick = open)
                .clearAndSetSemantics {
                    contentDescription = sentence.text
                    role = Role.Button
                    onClick(hint) {
                        open()
                        true
                    }
                }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Overlapping: each avatar tucks 8 dp under the next.
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            summary.avatars.take(SOCIAL_AVATARS).forEach { ref ->
                UserAvatar(
                    userId = ref.userId,
                    size = AvatarSize.Small,
                    fallbackName = ref.displayName,
                    modifier = Modifier.border(2.dp, ringColor, CircleShape),
                )
            }
        }
        Text(
            text = sentence,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
