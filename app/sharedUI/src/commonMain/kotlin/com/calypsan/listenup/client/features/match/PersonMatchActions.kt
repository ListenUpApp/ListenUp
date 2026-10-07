package com.calypsan.listenup.client.features.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.client.presentation.match.PersonMatchViewModel

/**
 * Everything a person can do on a person's Match details, as the screen asks for it. The route backs it with
 * [PersonMatchViewModel]; content tests back it with a recorder, so the screen renders from fixed state.
 */
interface PersonMatchActions {
    /** Searches for [query]; blank goes back to the automatic search. */
    fun search(query: String)

    /** Matches the person as [role]: As author | As narrator. */
    fun switchRole(role: ContributorRole)

    /** Runs Find again. */
    fun retry()

    /** Opens [key]'s Review. */
    fun pick(key: PersonCandidateKey)

    /** Leaves Review for the intact results. */
    fun backToResults()

    /** Reports whether results and Review sit side by side. */
    fun useTwoPane(enabled: Boolean)

    /** Picks the photo Apply writes, or Keep current. */
    fun choosePhoto(choice: ImageChoice)

    /** Ticks or unticks the biography. */
    fun setBiographyTicked(ticked: Boolean)

    /** Picks the biography's source, or Keep yours. */
    fun chooseBiographySource(choice: FieldChoice)

    /** Applies the photo and biography choices in one go. */
    fun apply()
}

/** [PersonMatchActions] straight onto the session's ViewModel. */
internal class ViewModelPersonMatchActions(
    private val viewModel: PersonMatchViewModel,
) : PersonMatchActions {
    override fun search(query: String) = viewModel.search(query)

    override fun switchRole(role: ContributorRole) = viewModel.switchRole(role)

    override fun retry() = viewModel.retry()

    override fun pick(key: PersonCandidateKey) = viewModel.pick(key)

    override fun backToResults() = viewModel.backToResults()

    override fun useTwoPane(enabled: Boolean) = viewModel.useTwoPane(enabled)

    override fun choosePhoto(choice: ImageChoice) = viewModel.choosePhoto(choice)

    override fun setBiographyTicked(ticked: Boolean) = viewModel.setBiographyTicked(ticked)

    override fun chooseBiographySource(choice: FieldChoice) = viewModel.chooseBiographySource(choice)

    override fun apply() = viewModel.apply()
}
