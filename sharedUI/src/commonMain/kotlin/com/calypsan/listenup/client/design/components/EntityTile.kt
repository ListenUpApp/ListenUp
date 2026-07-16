package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.sync.EntityKind
import kotlin.math.abs

private const val LOCATION_ITEM_TINT_ALPHA = 0.2f
private const val ICON_SIZE_RATIO = 0.46f
private const val INITIALS_SIZE_RATIO = 0.32f
private val ARTICLE_PREFIXES = listOf("the ", "a ", "an ")

/**
 * Muted, M3-friendly tint palette for [entityTint] — the same eight hues used across the Story
 * World mockups so a given entity's colour stays stable wherever it's rendered (tile, avatar
 * stack, evolution timeline).
 */
private val ENTITY_TINT_PALETTE =
    listOf(
        Color(0xFF3E6B8E),
        Color(0xFF8E3E6B),
        Color(0xFF5B5BD6),
        Color(0xFFC0562F),
        Color(0xFFC98A2E),
        Color(0xFF1F8A5B),
        Color(0xFF2E8B8E),
        Color(0xFF4A6572),
    )

/**
 * Deterministic tint for a Story World entity, derived from [seed] (typically the entity id) so
 * the same entity always renders the same colour without the server needing to assign one.
 */
internal fun entityTint(seed: String): Color = ENTITY_TINT_PALETTE[abs(seed.hashCode()) % ENTITY_TINT_PALETTE.size]

/**
 * Initials for a [EntityKind.CHARACTER] tile: the first letter of the first two words of [name],
 * after stripping a leading "The "/"A "/"An " article (case-insensitive). Single-word names fall
 * back to just their first letter.
 */
internal fun entityInitials(name: String): String {
    val withoutArticle =
        ARTICLE_PREFIXES.fold(name) { current, article ->
            if (current.lowercase().startsWith(article)) current.drop(article.length) else current
        }
    val words = withoutArticle.trim().split(" ").filter { it.isNotBlank() }
    return words
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString(separator = "")
}

/**
 * The canonical Story World entity glyph: a [EntityKind.CHARACTER] renders as a [ScallopBadge]
 * holding the entity's initials; [EntityKind.LOCATION] and [EntityKind.ITEM] render as a rounded
 * tile with a kind-appropriate icon. Every kind is tinted by [entityTint] so the same entity keeps
 * its colour across the entry list, avatar stacks, and the evolution timeline.
 *
 * @param name Entity display name — drives the initials shown for [EntityKind.CHARACTER].
 * @param kind Which Story World taxonomy this entity belongs to; drives the whole look.
 * @param tintSeed Stable seed (typically the entity id) feeding [entityTint].
 * @param size Edge length of the square tile.
 * @param modifier Modifier for the tile.
 */
@Composable
fun EntityTile(
    name: String,
    kind: EntityKind,
    tintSeed: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val tint = entityTint(tintSeed)
    when (kind) {
        EntityKind.CHARACTER -> {
            ScallopBadge(modifier = modifier, size = size, containerColor = tint) {
                Text(
                    text = entityInitials(name),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * INITIALS_SIZE_RATIO).sp,
                )
            }
        }

        EntityKind.LOCATION, EntityKind.ITEM -> {
            val background =
                tint
                    .copy(
                        alpha = LOCATION_ITEM_TINT_ALPHA,
                    ).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
            Box(
                modifier =
                    modifier
                        .size(size)
                        .clip(RoundedCornerShape(size / 4))
                        .background(background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (kind == EntityKind.LOCATION) Icons.Outlined.Place else Icons.Outlined.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(size * ICON_SIZE_RATIO),
                    tint = tint,
                )
            }
        }
    }
}
