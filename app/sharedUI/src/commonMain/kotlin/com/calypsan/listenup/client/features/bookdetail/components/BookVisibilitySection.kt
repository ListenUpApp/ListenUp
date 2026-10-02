package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.presentation.bookdetail.HiddenFromNames
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_visibility_add_to_collection
import listenup.composeapp.generated.resources.book_visibility_admins_only
import listenup.composeapp.generated.resources.book_visibility_every_member
import listenup.composeapp.generated.resources.book_visibility_hidden_from
import listenup.composeapp.generated.resources.book_visibility_hidden_from_all
import listenup.composeapp.generated.resources.book_visibility_in_collections_one
import listenup.composeapp.generated.resources.book_visibility_in_collections_plural
import listenup.composeapp.generated.resources.book_visibility_names_one_other
import listenup.composeapp.generated.resources.book_visibility_names_others
import listenup.composeapp.generated.resources.book_visibility_names_two
import listenup.composeapp.generated.resources.book_visibility_reason_everyone_many
import listenup.composeapp.generated.resources.book_visibility_reason_everyone_one
import listenup.composeapp.generated.resources.book_visibility_reason_members_many
import listenup.composeapp.generated.resources.book_visibility_reason_members_one
import listenup.composeapp.generated.resources.book_visibility_reason_nobody_many
import listenup.composeapp.generated.resources.book_visibility_reason_nobody_one
import listenup.composeapp.generated.resources.book_visibility_reason_stranded
import listenup.composeapp.generated.resources.book_visibility_restoring
import listenup.composeapp.generated.resources.book_visibility_show_all
import listenup.composeapp.generated.resources.book_visibility_show_to_all
import listenup.composeapp.generated.resources.book_visibility_title
import org.jetbrains.compose.resources.stringResource

private val CardCorner = 20.dp

/**
 * Book Detail's Visibility card — admins only, and only for a [BookVisibility.Restricted] or
 * [BookVisibility.Stranded] book; anything else renders nothing. The headline says who cannot see
 * the book; the collections underneath are the reason, and each opens its collection when
 * [onCollectionClick] is given (null renders plain labels — the frozen desktop has no route).
 *
 * Stranded is the one problem state: amber, a warning glyph in place of the lock, and two fixes. "Show to all members"
 * does not ask first (spec §7) — it restores what was meant to be public, and is undoable.
 */
@Composable
fun BookVisibilitySection(
    visibility: BookVisibility,
    isRestoring: Boolean,
    onCollectionClick: ((collectionId: String) -> Unit)?,
    onShowToAllMembers: () -> Unit,
    onAddToCollection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (visibility) {
        is BookVisibility.Restricted -> {
            VisibilityCard(
                container = MaterialTheme.colorScheme.surfaceContainer,
                glyph = Icons.Outlined.Lock,
                titleColor = MaterialTheme.colorScheme.primary,
                modifier = modifier,
            ) {
                RestrictedBody(visibility, onCollectionClick)
            }
        }

        BookVisibility.Stranded -> {
            VisibilityCard(
                container = MaterialTheme.colorScheme.tertiaryContainer,
                glyph = Icons.Outlined.WarningAmber,
                titleColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = modifier,
            ) {
                StrandedBody(isRestoring, onShowToAllMembers, onAddToCollection)
            }
        }

        BookVisibility.Public, BookVisibility.Held -> {
            Unit
        }
    }
}

@Composable
private fun VisibilityCard(
    container: Color,
    glyph: ImageVector,
    titleColor: Color,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(CardCorner), color = container, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(glyph, contentDescription = null, tint = titleColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(Res.string.book_visibility_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TonalLabel(label = stringResource(Res.string.book_visibility_admins_only))
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestrictedBody(
    visibility: BookVisibility.Restricted,
    onCollectionClick: ((String) -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collections = visibility.collections
    // An empty Members list breaks HiddenFrom's invariant; read it as the Nobody it means.
    val hiddenFrom = visibility.hiddenFrom.takeUnless { it is HiddenFrom.Members && it.names.isEmpty() } ?: HiddenFrom.Nobody
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = headline(hiddenFrom, expanded),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = reason(hiddenFrom, collections),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (hiddenFrom is HiddenFrom.Members && HiddenFromNames.canExpand(hiddenFrom.names, expanded)) {
        val haptics = LocalHaptics.current
        TextButton(onClick = {
            haptics.press()
            expanded = true
        }) {
            Text(stringResource(Res.string.book_visibility_show_all, hiddenFrom.names.size))
        }
    }
    Column {
        Text(
            text =
                if (collections.size == 1) {
                    stringResource(Res.string.book_visibility_in_collections_one)
                } else {
                    stringResource(Res.string.book_visibility_in_collections_plural, collections.size)
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            collections.forEach { collection -> CollectionName(collection, onCollectionClick) }
        }
    }
}

@Composable
private fun CollectionName(
    collection: CollectionRef,
    onCollectionClick: ((String) -> Unit)?,
) {
    if (onCollectionClick != null) {
        PillChip(
            label = collection.name,
            onClick = { onCollectionClick(collection.id) },
            leadingIcon = Icons.Outlined.CollectionsBookmark,
        )
    } else {
        TonalLabel(label = collection.name, icon = Icons.Outlined.CollectionsBookmark)
    }
}

@Composable
private fun StrandedBody(
    isRestoring: Boolean,
    onShowToAllMembers: () -> Unit,
    onAddToCollection: () -> Unit,
) {
    // The one problem state: the warning glyph sits in the header, so the body is words and fixes.
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = stringResource(Res.string.book_visibility_hidden_from_all),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = stringResource(Res.string.book_visibility_reason_stranded),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ListenUpButton(
            text =
                if (isRestoring) {
                    stringResource(Res.string.book_visibility_restoring)
                } else {
                    stringResource(Res.string.book_visibility_show_to_all)
                },
            onClick = onShowToAllMembers,
            enabled = !isRestoring,
            fillMaxWidth = false,
        )
        ListenUpButton(
            text = stringResource(Res.string.book_visibility_add_to_collection),
            onClick = onAddToCollection,
            filled = false,
            fillMaxWidth = false,
        )
    }
}

/** "Hidden from …" from the names, "Every member can see it", or "Hidden from all members". */
@Composable
private fun headline(
    hiddenFrom: HiddenFrom,
    expanded: Boolean,
): String =
    when (hiddenFrom) {
        HiddenFrom.Nobody -> {
            stringResource(Res.string.book_visibility_every_member)
        }

        HiddenFrom.Everyone -> {
            stringResource(Res.string.book_visibility_hidden_from_all)
        }

        is HiddenFrom.Members -> {
            stringResource(
                Res.string.book_visibility_hidden_from,
                nameList(hiddenFrom.names, expanded),
            )
        }
    }

/** "Alice", "Alice and Ben", "Alice, Ben and Cy", or "Alice, Dev, Hana and 2 others". */
@Composable
private fun nameList(
    names: List<String>,
    expanded: Boolean,
): String {
    val summary = HiddenFromNames.summarize(names, expanded)
    val shown = summary.shown
    return when {
        summary.othersCount == 1 -> {
            stringResource(Res.string.book_visibility_names_one_other, shown.joinToString(", "))
        }

        summary.othersCount > 1 -> {
            stringResource(Res.string.book_visibility_names_others, shown.joinToString(", "), summary.othersCount)
        }

        shown.size == 1 -> {
            shown.single()
        }

        else -> {
            stringResource(Res.string.book_visibility_names_two, shown.dropLast(1).joinToString(", "), shown.last())
        }
    }
}

/** Why: which collections let people in, worded for one collection or several. */
@Composable
private fun reason(
    hiddenFrom: HiddenFrom,
    collections: List<CollectionRef>,
): String {
    val only = collections.singleOrNull()?.name
    return when (hiddenFrom) {
        is HiddenFrom.Members -> {
            only?.let { stringResource(Res.string.book_visibility_reason_members_one, it) }
                ?: stringResource(Res.string.book_visibility_reason_members_many)
        }

        HiddenFrom.Nobody -> {
            only?.let { stringResource(Res.string.book_visibility_reason_nobody_one, it) }
                ?: stringResource(Res.string.book_visibility_reason_nobody_many)
        }

        HiddenFrom.Everyone -> {
            only?.let { stringResource(Res.string.book_visibility_reason_everyone_one, it) }
                ?: stringResource(Res.string.book_visibility_reason_everyone_many)
        }
    }
}
