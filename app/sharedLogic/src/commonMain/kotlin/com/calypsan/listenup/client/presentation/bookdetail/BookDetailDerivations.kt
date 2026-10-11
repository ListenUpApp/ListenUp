package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.PlaybackPosition

/** Where the reader is with a book of [durationMs], from its saved [position]. Pure. */
internal fun readingStage(
    position: PlaybackPosition?,
    durationMs: Long,
): ReadingStage =
    when {
        position?.isFinished == true -> ReadingStage.Finished
        position != null && durationMs > 0 && position.positionMs > 0 -> ReadingStage.InProgress
        else -> ReadingStage.NotStarted
    }

/** Which sections the device itself knows to be absent. Readers, Ratings and Hardcover are the platform's call. */
internal data class SectionPresence(
    val hasSeries: Boolean,
    val hasChapters: Boolean,
)

private val notStartedOrder =
    listOf(
        BookDetailSection.About,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Readers,
        BookDetailSection.Series,
        BookDetailSection.Chapters,
        BookDetailSection.Details,
    )

private val inProgressOrder =
    listOf(
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Series,
        BookDetailSection.Readers,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Details,
    )

private val finishedOrder =
    listOf(
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Readers,
        BookDetailSection.Series,
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Details,
    )

/**
 * The sections below the action unit, in the order [stage] puts them (spec §2.4), minus those [presence] knows are
 * absent. Pure; the ViewModel latches the answer once per visit.
 */
internal fun sectionOrderFor(
    stage: ReadingStage,
    presence: SectionPresence,
): List<BookDetailSection> {
    val order =
        when (stage) {
            ReadingStage.NotStarted -> notStartedOrder
            ReadingStage.InProgress -> inProgressOrder
            ReadingStage.Finished -> finishedOrder
        }
    return order.filter { section ->
        when (section) {
            BookDetailSection.Series -> presence.hasSeries
            BookDetailSection.Chapters -> presence.hasChapters
            else -> true
        }
    }
}

/**
 * Book Detail's primary action. Disabled only for [PrimaryAction.UnavailableOffline]: the server is unreachable
 * ([showServerWarning]) and nothing of the book is on the device (![canPlay] — `canPlay` is "some audio downloaded,
 * or the server reachable").
 */
internal fun primaryActionFor(
    stage: ReadingStage,
    showServerWarning: Boolean,
    canPlay: Boolean,
): PrimaryAction =
    when {
        showServerWarning && !canPlay -> PrimaryAction.UnavailableOffline
        stage == ReadingStage.InProgress -> PrimaryAction.Resume
        stage == ReadingStage.Finished -> PrimaryAction.ListenAgain
        else -> PrimaryAction.Play
    }
