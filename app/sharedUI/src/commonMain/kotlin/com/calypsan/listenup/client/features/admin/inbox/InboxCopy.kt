package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_held_count_a11y
import listenup.composeapp.generated.resources.admin_held_count_a11y_plural
import listenup.composeapp.generated.resources.admin_inbox_entry_a11y
import listenup.composeapp.generated.resources.admin_inbox_entry_a11y_plural
import listenup.composeapp.generated.resources.admin_inbox_entry_title
import listenup.composeapp.generated.resources.admin_inbox_entry_title_plural
import org.jetbrains.compose.resources.stringResource

/** "Inbox · 3 new books" — the Library entry's title. */
@Composable
internal fun inboxEntryTitle(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.admin_inbox_entry_title, count)
    } else {
        stringResource(Res.string.admin_inbox_entry_title_plural, count)
    }

/** "Inbox, 3 books waiting for review" — the Library entry as one control, read aloud. */
@Composable
internal fun inboxEntryDescription(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.admin_inbox_entry_a11y, count)
    } else {
        stringResource(Res.string.admin_inbox_entry_a11y_plural, count)
    }

/**
 * "3 books waiting for review" — the nav badge read aloud. The true count, not the badge's "99+"
 * cap: that cap keeps the pill from stretching, which a screen reader does not need.
 */
@Composable
internal fun heldWaitingDescription(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.admin_held_count_a11y, count)
    } else {
        stringResource(Res.string.admin_held_count_a11y_plural, count)
    }
