package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_restricted_a11y
import org.jetbrains.compose.resources.stringResource

/**
 * The ids of the books an admin should see locked, published once at the authenticated root from
 * `RestrictedBooksViewModel`. Empty by default — a member's device, a preview, the frozen desktop —
 * so a card outside a provider simply shows no lock. Not static: the set changes as books join
 * and leave collections.
 */
val LocalRestrictedBookIds = compositionLocalOf<Set<String>> { emptySet() }

/**
 * The collection-visibility lock: a neutral disc in a cover's top-start corner, drawn only when
 * [bookId] is restricted. A fact about who else can see the book, so it never joins the top-end
 * listening badges (selection, now playing, completed, documents) and never moves. Not tappable —
 * it lives inside the card's one click target; its description joins the card's for TalkBack.
 *
 * A circle, not a scallop: the scallops are the listening-state family. Distinct from the inbox's
 * *Held* label, which no restricted book ever wears (the restricted set excludes held books).
 *
 * @param compact The row-cover size (covers ≤ 68dp): 20dp disc, 12dp glyph.
 */
@Composable
fun RestrictedBookMarker(
    bookId: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (bookId !in LocalRestrictedBookIds.current) return
    val disc = if (compact) 20.dp else 28.dp
    Box(
        modifier =
            modifier
                .shadow(elevation = 3.dp, shape = CircleShape)
                .size(disc)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.inverseSurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = stringResource(Res.string.library_restricted_a11y),
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(if (compact) 12.dp else 15.dp),
        )
    }
}
