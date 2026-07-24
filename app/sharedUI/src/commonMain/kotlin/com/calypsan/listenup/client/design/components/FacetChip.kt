package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/**
 * The three classification axes shown on Book Detail. Each book is described along three
 * independent dimensions, and each one gets a distinct chip treatment so the reader can tell
 * them apart at a glance:
 *
 * - [Genre] — "where it lives" (Epic Fantasy, Mystery). Rendered as an **outlined** pill:
 *   transparent fill, `outlineVariant` border, `onSurfaceVariant` text, no icon, a larger
 *   `labelLarge` label. The outlined-vs-filled contrast is the primary signal that genres are
 *   a different system from tags and moods.
 * - [Tag] — community tropes / shelving descriptors (Award Winner, Owned). Rendered as a
 *   **filled `secondaryContainer`** pill with a leading tag glyph.
 * - [Mood] — the affective axis, "how it feels" (Dark, Epic, Tense). Rendered as a **filled
 *   `tertiaryContainer`** pill with a leading mood glyph.
 *
 * The single [FacetChip] component switches its entire look on this enum, so there is exactly
 * one chip implementation backing all three rows.
 */
enum class BookFacet {
    Genre,
    Tag,
    Mood,
}

/**
 * Resolved visual treatment for a [BookFacet]. Colours are pulled live from
 * [MaterialTheme.colorScheme] so the chip honours the active expressive theme.
 */
private data class FacetStyle(
    val containerColor: Color?,
    val contentColor: Color,
    val borderColor: Color?,
    val leadingIcon: ImageVector?,
    val textStyle: TextStyle,
    val labelWeight: FontWeight,
    val contentPadding: PaddingValues,
    val iconSpacing: Dp,
)

@Composable
private fun BookFacet.style(): FacetStyle =
    when (this) {
        // Outlined: transparent fill + 1.5dp border, larger label, no icon.
        BookFacet.Genre -> {
            FacetStyle(
                containerColor = null,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                leadingIcon = null,
                textStyle = MaterialTheme.typography.labelLarge,
                labelWeight = FontWeight.Medium,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                iconSpacing = 0.dp,
            )
        }

        // Filled secondary with a leading tag glyph.
        BookFacet.Tag -> {
            FacetStyle(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                borderColor = null,
                leadingIcon = Icons.Default.Tag,
                textStyle = MaterialTheme.typography.labelMedium,
                labelWeight = FontWeight.Bold,
                contentPadding = PaddingValues(start = 11.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                iconSpacing = 7.dp,
            )
        }

        // Filled tertiary with a leading mood glyph.
        BookFacet.Mood -> {
            FacetStyle(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                borderColor = null,
                leadingIcon = Icons.Default.Mood,
                textStyle = MaterialTheme.typography.labelMedium,
                labelWeight = FontWeight.Bold,
                contentPadding = PaddingValues(start = 11.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                iconSpacing = 7.dp,
            )
        }
    }

/**
 * A single classification chip whose entire look — fill vs. border, content colour, leading
 * icon, and label size — is determined by [facet]. This is the one canonical chip backing the
 * Genres, Tags, and Moods rows on Book Detail (see [BookFacet] for the per-axis rationale).
 *
 * Fully-rounded ([RoundedCornerShape] of 50). Genre chips are outlined (border, no fill); Tag
 * and Mood chips are filled with a leading glyph. The chip is clickable only when [onClick] is
 * non-null — Genre and Tag chips browse by facet; Mood chips are display-only.
 *
 * @param label   The facet value to display.
 * @param facet   Which classification axis this chip belongs to; drives the whole look.
 * @param onClick Optional click handler; the chip is non-interactive when null.
 * @param modifier Modifier for the chip.
 */
@Composable
fun FacetChip(
    label: String,
    facet: BookFacet,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val style = facet.style()
    val shape = RoundedCornerShape(50)

    Row(
        modifier =
            modifier
                .clip(shape)
                .then(
                    if (style.containerColor != null) {
                        Modifier.background(style.containerColor)
                    } else {
                        Modifier
                    },
                ).then(
                    if (style.borderColor != null) {
                        Modifier.border(1.5.dp, style.borderColor, shape)
                    } else {
                        Modifier
                    },
                ).then(
                    if (onClick != null) {
                        Modifier.clickable {
                            haptics.selectionTick()
                            onClick()
                        }
                    } else {
                        Modifier
                    },
                ).padding(style.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(style.iconSpacing),
    ) {
        style.leadingIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = style.contentColor,
            )
        }
        Text(
            text = label,
            style = style.textStyle,
            fontWeight = style.labelWeight,
            color = style.contentColor,
        )
    }
}

/**
 * A wrapping row of [FacetChip]s, all sharing one [facet], one chip per item in [items]. Each
 * chip closes its click over its own item object via [label] — so two items whose [label] strings
 * collide stay independently routable (a plain `displayName → item` map would drop one). Wraps to
 * multiple lines via [FlowRow] with 8.dp gaps. Returns early when [items] is empty.
 *
 * @param items   The facet values, one chip each.
 * @param facet   The classification axis for every chip in the row.
 * @param label   Renders an item to its chip label.
 * @param onClick Optional per-item click handler; chips are non-interactive when null.
 * @param modifier Modifier for the row.
 * @param horizontalArrangement Horizontal arrangement for the chips (defaults to start-aligned,
 *                              8.dp gaps).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> FacetChipRow(
    items: List<T>,
    facet: BookFacet,
    label: (T) -> String,
    onClick: ((T) -> Unit)? = null,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.Start),
) {
    if (items.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FacetChip(
                label = label(item),
                facet = facet,
                onClick = onClick?.let { { it(item) } },
            )
        }
    }
}

/**
 * A wrapping row of [FacetChip]s for plain string facet values (genres, moods). Delegates to the
 * generic [FacetChipRow] with the identity label.
 *
 * @param labels  The facet values, one chip each.
 * @param facet   The classification axis for every chip in the row.
 * @param onClick Optional per-label click handler; chips are non-interactive when null.
 * @param modifier Modifier for the row.
 * @param horizontalArrangement Horizontal arrangement for the chips (defaults to start-aligned,
 *                              8.dp gaps).
 */
@Composable
fun FacetChipRow(
    labels: List<String>,
    facet: BookFacet,
    onClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.Start),
) {
    FacetChipRow(
        items = labels,
        facet = facet,
        label = { it },
        onClick = onClick,
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
    )
}
