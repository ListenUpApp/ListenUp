package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.api.sync.EntityKind
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_entries_count
import listenup.composeapp.generated.resources.story_world_entry_count
import listenup.composeapp.generated.resources.story_world_kind_character
import listenup.composeapp.generated.resources.story_world_kind_characters
import listenup.composeapp.generated.resources.story_world_kind_item
import listenup.composeapp.generated.resources.story_world_kind_items
import listenup.composeapp.generated.resources.story_world_kind_location
import listenup.composeapp.generated.resources.story_world_kind_locations

/** Glyph for an [EntityKind] tile — shared by the hub's kind cards, the entity list group headers, and the empty state. */
internal fun EntityKind.icon(): ImageVector =
    when (this) {
        EntityKind.CHARACTER -> Icons.Outlined.Person
        EntityKind.LOCATION -> Icons.Outlined.Place
        EntityKind.ITEM -> Icons.Outlined.Inventory2
    }

/** Plural display label for an [EntityKind] ("Characters", "Locations", "Items"). */
@Composable
internal fun EntityKind.pluralLabel(): String =
    when (this) {
        EntityKind.CHARACTER -> stringResource(Res.string.story_world_kind_characters)
        EntityKind.LOCATION -> stringResource(Res.string.story_world_kind_locations)
        EntityKind.ITEM -> stringResource(Res.string.story_world_kind_items)
    }

/** Singular display label for an [EntityKind] ("Character", "Location", "Item"). */
@Composable
internal fun EntityKind.singularLabel(): String =
    when (this) {
        EntityKind.CHARACTER -> stringResource(Res.string.story_world_kind_character)
        EntityKind.LOCATION -> stringResource(Res.string.story_world_kind_location)
        EntityKind.ITEM -> stringResource(Res.string.story_world_kind_item)
    }

/** "1 entry" / "N entries" — the correct plural form of a Story World entry count. */
@Composable
internal fun entryCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.story_world_entry_count, count)
    } else {
        stringResource(Res.string.story_world_entries_count, count)
    }
