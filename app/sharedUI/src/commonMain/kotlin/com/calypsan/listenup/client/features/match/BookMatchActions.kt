package com.calypsan.listenup.client.features.match

import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.BookMatchViewModel
import com.calypsan.listenup.client.presentation.match.LabelKind

/**
 * Everything a person can do on Match details, as the screen asks for it. The route backs it with
 * [BookMatchViewModel]; content tests back it with a recorder, so the screen renders from fixed state with
 * no Koin and no network.
 */
interface BookMatchActions {
    /** Searches for [query]; blank goes back to the automatic search. */
    fun search(query: String)

    /** Searches by title, author and length only. */
    fun searchByTitle()

    /** Searches [region]'s store for this search only. */
    fun chooseStore(region: MetadataLocale)

    /** Runs Find again. */
    fun retry()

    /** Opens [key]'s Review. */
    fun pick(key: BookCandidateKey)

    /** Leaves Review for the intact results. */
    fun backToResults()

    /** Reports whether results and Review sit side by side. */
    fun useTwoPane(enabled: Boolean)

    /** Ticks or unticks [field]. */
    fun setFieldTicked(
        field: BookField,
        ticked: Boolean,
    )

    /** Picks [field]'s source, or Keep yours. */
    fun chooseSource(
        field: BookField,
        choice: FieldChoice,
    )

    /** Picks the cover Apply writes. */
    fun chooseCover(choice: ImageChoice)

    /** Removes one of your labels. */
    fun removeYourLabel(
        kind: LabelKind,
        label: String,
    )

    /** Keeps a label you had removed. */
    fun restoreYourLabel(
        kind: LabelKind,
        label: String,
    )

    /** Selects or deselects a suggested label. */
    fun toggleSuggestion(
        kind: LabelKind,
        label: String,
    )

    /** Includes or leaves out the chapter names. */
    fun setChapterNamesIncluded(included: Boolean)

    /** Includes or leaves out one chapter's name. */
    fun toggleChapter(ordinal: Int)

    /** Applies every decision. */
    fun apply()
}

/** [BookMatchActions] straight onto the session's ViewModel. */
internal class ViewModelMatchActions(
    private val viewModel: BookMatchViewModel,
) : BookMatchActions {
    override fun search(query: String) = viewModel.search(query)

    override fun searchByTitle() = viewModel.searchByTitle()

    override fun chooseStore(region: MetadataLocale) = viewModel.chooseStoreForThisSearch(region)

    override fun retry() = viewModel.retry()

    override fun pick(key: BookCandidateKey) = viewModel.pick(key)

    override fun backToResults() = viewModel.backToResults()

    override fun useTwoPane(enabled: Boolean) = viewModel.useTwoPane(enabled)

    override fun setFieldTicked(
        field: BookField,
        ticked: Boolean,
    ) = viewModel.setFieldTicked(field, ticked)

    override fun chooseSource(
        field: BookField,
        choice: FieldChoice,
    ) = viewModel.chooseSource(field, choice)

    override fun chooseCover(choice: ImageChoice) = viewModel.chooseCover(choice)

    override fun removeYourLabel(
        kind: LabelKind,
        label: String,
    ) = viewModel.removeYourLabel(kind, label)

    override fun restoreYourLabel(
        kind: LabelKind,
        label: String,
    ) = viewModel.restoreYourLabel(kind, label)

    override fun toggleSuggestion(
        kind: LabelKind,
        label: String,
    ) = viewModel.toggleSuggestion(kind, label)

    override fun setChapterNamesIncluded(included: Boolean) = viewModel.setChapterNamesIncluded(included)

    override fun toggleChapter(ordinal: Int) = viewModel.toggleChapter(ordinal)

    override fun apply() = viewModel.apply()
}
