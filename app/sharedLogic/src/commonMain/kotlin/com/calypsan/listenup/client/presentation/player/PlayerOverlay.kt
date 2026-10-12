package com.calypsan.listenup.client.presentation.player

/**
 * Which picker or sheet is open over the player. Mutually exclusive: at most one at a time. Per view model
 * instance, so each screen owns its own (spec §4.2's ephemera).
 */
sealed interface PlayerOverlay {
    /** Nothing open. */
    data object None : PlayerOverlay

    /** The chapter list. */
    data object ChapterPicker : PlayerOverlay

    /** The speed picker. */
    data object SpeedPicker : PlayerOverlay

    /** Picker for volume boost — user-set gain layered on top of loudness normalization. */
    data object BoostPicker : PlayerOverlay

    /** The sleep timer sheet. */
    data object SleepTimer : PlayerOverlay

    /** Picker for the book's contributors, scoped to a single role via [type] (authors, narrators, etc.). */
    data class ContributorPicker(
        val type: ContributorPickerType,
    ) : PlayerOverlay
}

/**
 * Type of contributor picker to show.
 */
enum class ContributorPickerType {
    AUTHORS,
    NARRATORS,
}
