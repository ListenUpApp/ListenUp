package com.calypsan.listenup.client.features.bulkedit

import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_applied_one
import listenup.composeapp.generated.resources.bulk_edit_applied_plural
import org.jetbrains.compose.resources.getString

/**
 * What the app says once a bulk edit has actually landed.
 *
 * The editor pops the moment it succeeds, and the grid it returns to shows covers and titles —
 * not publishers — so without a word from the app a write to forty books looks exactly like a
 * write to none. This is that word.
 *
 * It is a suspend function rather than a composable because the sentence is needed *after* the
 * screen that produced it has left the composition; the snackbar is raised from the shell's scope.
 *
 * @param changedCount how many books the server actually changed.
 */
internal suspend fun bulkEditAppliedMessage(changedCount: Int): String =
    if (changedCount == 1) {
        getString(Res.string.bulk_edit_applied_one)
    } else {
        getString(Res.string.bulk_edit_applied_plural, changedCount)
    }
