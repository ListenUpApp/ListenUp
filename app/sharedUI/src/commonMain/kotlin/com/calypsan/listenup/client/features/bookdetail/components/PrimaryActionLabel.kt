package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction

/** The primary button's words. Preparing is the player's state, not the book's, so it overrides. */
internal enum class PrimaryLabel { Play, Resume, ListenAgain, UnavailableOffline, Preparing }

/** Pure: which label the primary shows. */
internal fun primaryLabelFor(
    action: PrimaryAction,
    isPreparing: Boolean,
): PrimaryLabel =
    when {
        isPreparing -> PrimaryLabel.Preparing
        action == PrimaryAction.Resume -> PrimaryLabel.Resume
        action == PrimaryAction.ListenAgain -> PrimaryLabel.ListenAgain
        action == PrimaryAction.UnavailableOffline -> PrimaryLabel.UnavailableOffline
        else -> PrimaryLabel.Play
    }

/** Spec §2.9 / N8: the primary is disabled for Unavailable offline and nothing else. */
internal fun isPrimaryEnabled(action: PrimaryAction): Boolean = action != PrimaryAction.UnavailableOffline
