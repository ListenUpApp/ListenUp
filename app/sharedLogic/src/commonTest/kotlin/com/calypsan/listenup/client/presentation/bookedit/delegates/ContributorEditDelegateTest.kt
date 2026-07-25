package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.MIN_SEARCH_QUERY_LENGTH
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bookedit.ContributorRole
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ContributorEditDelegate]'s per-role debounced search-query gating.
 *
 * The client's local FTS5 index uses `tokenize='trigram'`, which cannot match a query
 * shorter than [MIN_SEARCH_QUERY_LENGTH] — a query below the floor must never reach
 * [ContributorRepository.searchContributors], let alone the index itself.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ContributorEditDelegateTest :
    FunSpec({

        class Fixture {
            val state = MutableStateFlow(BookEditUiState(bookId = "book-1"))
            val contributorRepository: ContributorRepository = mock()

            fun build(scope: CoroutineScope): ContributorEditDelegate =
                ContributorEditDelegate(
                    state = state,
                    contributorRepository = contributorRepository,
                    scope = scope,
                    onChangesMade = {},
                )
        }

        test("updateSearchQuery below MIN_SEARCH_QUERY_LENGTH never calls searchContributors") {
            runTest {
                val fixture = Fixture()
                val delegate = fixture.build(backgroundScope)
                delegate.setupRoleSearch(ContributorRole.AUTHOR)

                delegate.updateSearchQuery(ContributorRole.AUTHOR, "ab")
                advanceTimeBy(SEARCH_DEBOUNCE_MS + DEBOUNCE_MARGIN_MS)
                runCurrent()

                verifySuspend(VerifyMode.not) {
                    fixture.contributorRepository.searchContributors(any(), any())
                }
            }
        }

        test("updateSearchQuery at MIN_SEARCH_QUERY_LENGTH calls searchContributors and populates results") {
            runTest {
                val fixture = Fixture()
                everySuspend { fixture.contributorRepository.searchContributors(any(), any()) } returns
                    ContributorSearchResponse(
                        contributors = listOf(ContributorSearchResult(id = "contrib-1", name = "Brandon", bookCount = 5)),
                        isOfflineResult = true,
                        tookMs = 1L,
                    )
                val delegate = fixture.build(backgroundScope)
                delegate.setupRoleSearch(ContributorRole.AUTHOR)

                delegate.updateSearchQuery(ContributorRole.AUTHOR, "bra")
                advanceTimeBy(SEARCH_DEBOUNCE_MS + DEBOUNCE_MARGIN_MS)
                runCurrent()

                verifySuspend { fixture.contributorRepository.searchContributors("bra", limit = 10) }
                fixture.state.value
                    .searchResultsForRole(ContributorRole.AUTHOR)
                    .map { it.id } shouldContainExactly listOf("contrib-1")
            }
        }
    })

/** The delegate's debounce window; virtual time must clear it before the search fires. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Slack past the debounce so the test never sits exactly on the boundary. */
private const val DEBOUNCE_MARGIN_MS = 50L
