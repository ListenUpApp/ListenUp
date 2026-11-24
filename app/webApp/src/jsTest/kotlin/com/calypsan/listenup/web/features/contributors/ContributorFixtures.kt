package com.calypsan.listenup.web.features.contributors

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.web.design.LibraryFacet

/** Renders [ContributorsPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testContributorsPage(
    state: List<ContributorWithBookCount>?,
    role: ContributorRole,
    onSelectFacet: (LibraryFacet) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
    sortState: SortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
    onEvent: (LibraryUiEvent) -> Unit = {},
    letters: List<LetterSection> = emptyList(),
) {
    ContributorsPage(
        state = state,
        role = role,
        onSelectFacet = onSelectFacet,
        onOpenContributor = onOpenContributor,
        sortState = sortState,
        onEvent = onEvent,
        letters = letters,
    )
}

/**
 * One contributor row's worth of data — enough for a spec to drive the letter-grouping, role-chip
 * and row contracts without a database behind them. Shared rather than duplicated, the same call
 * `FakeLibrary.kt` and `BookDetailFixtures.kt` make for their own features.
 */
internal fun contributor(
    id: String,
    name: String,
    bookCount: Int = 1,
    imagePath: String? = null,
    imageFrame: ImageFrame? = null,
): ContributorWithBookCount =
    ContributorWithBookCount(
        contributor = Contributor(id = ContributorId(id), name = name, imagePath = imagePath, imageFrame = imageFrame),
        bookCount = bookCount,
    )
