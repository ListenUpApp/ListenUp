package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.features.storyworld.EntityDetailScreen
import com.calypsan.listenup.client.features.storyworld.StoryWorldEntityListScreen
import com.calypsan.listenup.client.features.storyworld.StoryWorldHubScreen
import com.calypsan.listenup.client.navigation.StoryWorldEntities
import com.calypsan.listenup.client.navigation.StoryWorldEntityDetail
import com.calypsan.listenup.client.navigation.StoryWorldHub

/** Story World navigation entries — the hub, the entity list, and the entity detail page. */
internal fun EntryProviderScope<NavKey>.storyWorldEntries(backStack: NavBackStack<NavKey>) {
    entry<StoryWorldHub> { args ->
        StoryWorldHubScreen(
            seriesId = args.seriesId,
            bookId = args.bookId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onEntityClick = { entityId ->
                backStack.add(StoryWorldEntityDetail(entityId))
            },
            onKindClick = { kind ->
                backStack.add(StoryWorldEntities(seriesId = args.seriesId, bookId = args.bookId, kind = kind.name))
            },
        )
    }
    entry<StoryWorldEntities> { args ->
        StoryWorldEntityListScreen(
            seriesId = args.seriesId,
            bookId = args.bookId,
            kindFilter = args.kind?.let { kind -> EntityKind.entries.firstOrNull { it.name == kind } },
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onEntityClick = { entityId ->
                backStack.add(StoryWorldEntityDetail(entityId))
            },
        )
    }
    entry<StoryWorldEntityDetail> { args ->
        EntityDetailScreen(
            entityId = args.entityId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}
