package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ChapterNamesReview
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldDecision
import com.calypsan.listenup.api.dto.match.FieldReview
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetChange
import com.calypsan.listenup.api.dto.match.LabelSetReview
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.metadata.BookField

/**
 * What a person changed in one candidate's Review, as deltas over the server's defaults.
 *
 * Kept as deltas, not as a resolved copy, so a reloaded Review (after `ReviewOutdated`) or a re-picked candidate
 * re-resolves against whatever the server now offers: a choice whose option id survived is kept, one that
 * vanished falls back to the server's default. Nothing here can name an option the server didn't offer.
 */
internal data class ReviewChoices(
    internal val fields: Map<BookField, FieldChoice> = emptyMap(),
    internal val lastOption: Map<BookField, String> = emptyMap(),
    internal val cover: ImageChoice? = null,
    internal val removedYours: Map<LabelKind, Set<String>> = emptyMap(),
    internal val deselectedSuggestions: Map<LabelKind, Set<String>> = emptyMap(),
    internal val chapterNamesIncluded: Boolean = true,
    internal val deselectedChapters: Set<Int> = emptySet(),
) {
    fun choose(
        field: BookField,
        choice: FieldChoice,
    ): ReviewChoices =
        copy(
            fields = fields + (field to choice),
            lastOption = if (choice is FieldChoice.Option) lastOption + (field to choice.optionId) else lastOption,
        )

    fun toggleLabel(
        bucket: Map<LabelKind, Set<String>>,
        kind: LabelKind,
        label: String,
        on: Boolean,
    ): Map<LabelKind, Set<String>> {
        val current = bucket[kind].orEmpty()
        return bucket + (kind to if (on) current + label else current - label)
    }
}

/** The choice Apply sends for [review]: the person's, if its option still exists, else the server's default. */
internal fun ReviewChoices.choiceFor(review: FieldReview): FieldChoice =
    when (val explicit = fields[review.field]) {
        null -> {
            review.defaultChoice
        }

        FieldChoice.KeepCurrent -> {
            FieldChoice.KeepCurrent
        }

        is FieldChoice.Option -> {
            if (review.options.any {
                    it.optionId == explicit.optionId
                }
            ) {
                explicit
            } else {
                review.defaultChoice
            }
        }
    }

/** The option shown as Proposed: the chosen one, else the one re-ticking restores. */
private fun ReviewChoices.proposedFor(review: FieldReview): FieldOptionUi {
    val choice = choiceFor(review)
    val wanted =
        (choice as? FieldChoice.Option)?.optionId
            ?: lastOption[review.field]
            ?: (review.defaultChoice as? FieldChoice.Option)?.optionId
    val option = review.options.firstOrNull { it.optionId == wanted } ?: review.options.first()
    return FieldOptionUi(option.optionId, option.value, option.sources)
}

/** Ticking a field restores the last option chosen for it, else the server's pick, else the route's first. */
internal fun ReviewChoices.setTicked(
    review: FieldReview,
    ticked: Boolean,
): ReviewChoices =
    if (ticked) {
        choose(review.field, FieldChoice.Option(proposedFor(review).optionId))
    } else {
        val wasTicked = choiceFor(review) as? FieldChoice.Option
        copy(
            fields = fields + (review.field to FieldChoice.KeepCurrent),
            lastOption = if (wasTicked != null) lastOption + (review.field to wasTicked.optionId) else lastOption,
        )
    }

internal fun ReviewChoices.coverFor(review: BookMatchReview): ImageChoice =
    when (val explicit = cover) {
        null -> {
            review.cover.defaultChoice
        }

        ImageChoice.KeepCurrent -> {
            ImageChoice.KeepCurrent
        }

        is ImageChoice.Candidate -> {
            if (review.cover.options.any { it.optionId == explicit.optionId }) explicit else review.cover.defaultChoice
        }
    }

private fun ReviewChoices.labelsFor(
    kind: LabelKind,
    review: LabelSetReview,
): LabelSetUi {
    val removed = removedYours[kind].orEmpty()
    val deselected = deselectedSuggestions[kind].orEmpty()
    return LabelSetUi(
        yours = review.yours.map { YourLabelUi(it, removed = it in removed) },
        suggested = review.suggested.map { SuggestionUi(it.label, it.sources, selected = it.label !in deselected) },
    )
}

private fun ReviewChoices.chapterNamesFor(review: ChapterNamesReview): ChapterNamesUi =
    when (review) {
        ChapterNamesReview.Unavailable -> {
            ChapterNamesUi.Hidden
        }

        is ChapterNamesReview.CountMismatch -> {
            ChapterNamesUi.CountMismatch(review.source, review.yours, review.theirs)
        }

        is ChapterNamesReview.Available -> {
            if (review.rows.isEmpty()) {
                ChapterNamesUi.Hidden
            } else {
                ChapterNamesUi.Available(
                    source = review.source,
                    rows =
                        review.rows.map { row ->
                            ChapterRowUi(
                                ordinal = row.ordinal,
                                yours = row.yours,
                                theirs = row.theirs,
                                selected = row.ordinal !in deselectedChapters,
                            )
                        },
                    unchangedCount = review.unchangedCount,
                    included = chapterNamesIncluded,
                )
            }
        }
    }

/** Projects [review] and these choices into what Review renders. Pure, so every platform and test agrees. */
internal fun ReviewChoices.project(
    candidate: CandidateUi,
    review: BookMatchReview,
    currentCoverPath: String?,
    applying: Boolean,
    applyError: AppError?,
): ReviewUiState.Ready {
    val shown = review.fields.filter { it.options.isNotEmpty() }
    val fieldUis =
        shown.map { fr ->
            FieldUi(
                field = fr.field,
                state = fr.state,
                current = fr.current,
                options = fr.options.map { FieldOptionUi(it.optionId, it.value, it.sources) },
                choice = choiceFor(fr),
                proposed = proposedFor(fr),
                handEdit = fr.handEdit,
            )
        }
    val changes = fieldUis.filter { it.state == FieldState.CHANGES }
    val fillsGap = fieldUis.filter { it.state == FieldState.FILLS_GAP }
    val youEdited = fieldUis.filter { it.state == FieldState.USER_EDITED }
    val cover =
        CoverUi(
            current = review.cover.current,
            currentCoverPath = currentCoverPath,
            options = review.cover.options,
            choice = coverFor(review),
        )
    val genres = labelsFor(LabelKind.GENRES, review.genres)
    val moods = labelsFor(LabelKind.MOODS, review.moods)
    val chapterNames = chapterNamesFor(review.chapterNames)
    val chapterCount = (chapterNames as? ChapterNamesUi.Available)?.applyCount ?: 0
    val tickedFields = (changes + fillsGap + youEdited).count { it.isTicked }
    val labelFields = listOf(genres, moods).count { it.changes }
    return ReviewUiState.Ready(
        candidate = candidate,
        summary =
            WhatWillChange(
                coverSource = cover.chosen?.source,
                changeCount = changes.count { it.isTicked },
                gapCount = fillsGap.count { it.isTicked },
                labelsAdded = genres.added.size + moods.added.size,
                labelsRemoved = genres.removedLabels.size + moods.removedLabels.size,
                chapterNameCount = chapterCount,
                keptEditedCount = youEdited.count { !it.isTicked },
            ),
        cover = cover,
        changes = changes,
        fillsGap = fillsGap,
        youEdited = youEdited,
        genres = genres,
        moods = moods,
        chapterNames = chapterNames,
        alreadySame = shown.filter { it.state == FieldState.SAME }.map { it.field },
        alreadySameNames = shown.filter { it.state == FieldState.SAME }.map { it.field.name },
        lengthAlreadySame = candidate.reasons.any { it is MatchReason.SameLength },
        applyBar =
            ApplySummary(
                fieldCount = tickedFields + labelFields,
                coverChanges = cover.chosen != null,
                chapterNameCount = chapterCount,
            ),
        applying = applying,
        applyError = applyError,
    )
}

/** The one Apply request: every field's decision, the cover, both label sets and the chapter ordinals. */
internal fun ReviewChoices.toApply(review: BookMatchReview): BookMatchApply {
    val genres = labelsFor(LabelKind.GENRES, review.genres)
    val moods = labelsFor(LabelKind.MOODS, review.moods)
    val chapters = chapterNamesFor(review.chapterNames) as? ChapterNamesUi.Available
    return BookMatchApply(
        candidate = review.candidate,
        region = review.region,
        basedOnRevision = review.basedOnRevision,
        fields = review.fields.filter { it.options.isNotEmpty() }.map { FieldDecision(it.field, choiceFor(it)) },
        cover = coverFor(review),
        genres = LabelSetChange(add = genres.added, remove = genres.removedLabels),
        moods = LabelSetChange(add = moods.added, remove = moods.removedLabels),
        chapterOrdinals =
            chapters
                ?.takeIf { it.included }
                ?.run { rows.filter { it.selected }.map { it.ordinal } }
                .orEmpty(),
    )
}
