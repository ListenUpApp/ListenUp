package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.storyworld.AnchorLabel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_anchor_label_always
import listenup.composeapp.generated.resources.story_world_anchor_label_beginning
import listenup.composeapp.generated.resources.story_world_anchor_label_chapter
import listenup.composeapp.generated.resources.story_world_anchor_label_time

/**
 * Renders an [AnchorLabel] as the display string shown in an [com.calypsan.listenup.client.design.components.AnchorChip].
 *
 * [AnchorLabel.BookOnly] renders its book label verbatim (no further formatting is meaningful
 * once an entry is anchored to a book with no in-book position); every other variant fills its
 * `story_world_anchor_label_*` format string.
 */
@Composable
internal fun anchorLabelText(anchor: AnchorLabel): String =
    when (anchor) {
        is AnchorLabel.AlwaysVisible -> {
            stringResource(Res.string.story_world_anchor_label_always)
        }

        is AnchorLabel.BookOnly -> {
            anchor.bookLabel
        }

        is AnchorLabel.Beginning -> {
            stringResource(Res.string.story_world_anchor_label_beginning, anchor.bookLabel)
        }

        is AnchorLabel.AtChapter -> {
            stringResource(Res.string.story_world_anchor_label_chapter, anchor.bookLabel, anchor.chapterTitle)
        }

        is AnchorLabel.AtTime -> {
            stringResource(Res.string.story_world_anchor_label_time, anchor.bookLabel, anchor.formattedTime)
        }
    }
