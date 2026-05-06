package com.calypsan.listenup.client.presentation.contributormetadata

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorMetadataCandidate
import com.calypsan.listenup.client.domain.repository.ContributorMetadataProfile
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ContributorMetadataViewModel.
 *
 * Tests cover:
 * - Initialization and synchronous state reset (prevents stale state bugs)
 * - Search functionality
 * - Candidate selection and profile loading
 * - Metadata application with image download
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorMetadataViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixture ==========

    private class TestFixture {
        val contributorRepository: ContributorRepository = mock()
        val metadataRepository: MetadataRepository = mock()
        val applyContributorMetadataUseCase: ApplyContributorMetadataUseCase = mock()

        fun build(): ContributorMetadataViewModel =
            ContributorMetadataViewModel(
                contributorRepository = contributorRepository,
                metadataRepository = metadataRepository,
                applyContributorMetadataUseCase = applyContributorMetadataUseCase,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Test Data Factories ==========

    private fun createContributor(
        id: String = "contributor-1",
        name: String = "Stephen King",
    ): Contributor =
        Contributor(
            id =
                com.calypsan.listenup.client.core
                    .ContributorId(id),
            name = name,
            description = null,
            imagePath = null,
        )

    private fun createSearchResult(
        asin: String = "B001ASIN01",
        name: String = "Stephen King",
        imageUrl: String? = "https://example.com/image.jpg",
    ): ContributorMetadataCandidate =
        ContributorMetadataCandidate(
            asin = asin,
            name = name,
            imageUrl = imageUrl,
            description = "142 titles",
        )

    private fun createProfile(
        asin: String = "B001ASIN01",
        name: String = "Stephen King",
        biography: String? = "Stephen King is a prolific author...",
        imageUrl: String? = "https://example.com/image.jpg",
    ): ContributorMetadataProfile =
        ContributorMetadataProfile(
            asin = asin,
            name = name,
            biography = biography,
            imageUrl = imageUrl,
        )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initialization Tests ==========

    @Test
    fun `init synchronously resets state to prevent stale state bugs`() =
        runTest {
            // Given - ViewModel with stale state from previous contributor
            val fixture = createFixture()
            val viewModel = fixture.build()

            // Simulate stale state (e.g., applySuccess from previous contributor)
            // We can't set this directly, but we can verify the fix works by checking
            // that init() synchronously sets contributorId before any async work

            val contributor = createContributor(id = "contributor-2", name = "Neil Gaiman")
            every { fixture.contributorRepository.observeById("contributor-2") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns emptyList()

            // When
            viewModel.init("contributor-2")

            // Then - synchronous state reset happens immediately (before advanceUntilIdle)
            val stateBeforeAsync = viewModel.state.value
            assertEquals("contributor-2", stateBeforeAsync.contributorId)
            assertFalse(stateBeforeAsync.applySuccess) // Reset from default
            assertNull(stateBeforeAsync.currentContributor) // Not loaded yet
            assertEquals("", stateBeforeAsync.searchQuery) // Reset
            assertTrue(stateBeforeAsync.searchResults.isEmpty()) // Reset
        }

    @Test
    fun `init loads contributor and pre-fills search query`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor(name = "Stephen King")
            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors("Stephen King", "us") } returns
                listOf(createSearchResult())

            val viewModel = fixture.build()

            // When
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertEquals("contributor-1", state.contributorId)
            assertEquals(contributor, state.currentContributor)
            assertEquals("Stephen King", state.searchQuery)
        }

    @Test
    fun `init auto-searches with contributor name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor(name = "Stephen King")
            val searchResults = listOf(createSearchResult())

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors("Stephen King", "us") } returns searchResults

            val viewModel = fixture.build()

            // When
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertEquals(searchResults, state.searchResults)
            verifySuspend(VerifyMode.exactly(1)) {
                fixture.metadataRepository.searchContributors("Stephen King", "us")
            }
        }

    @Test
    fun `init does not auto-search when contributor name is blank`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor(name = "")
            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)

            val viewModel = fixture.build()

            // When
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // Then - search should not be called
            verifySuspend(VerifyMode.exactly(0)) {
                fixture.metadataRepository.searchContributors(any(), any())
            }
        }

    // ========== Search Tests ==========

    @Test
    fun `search updates state with results`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val searchResults =
                listOf(
                    createSearchResult(asin = "B001", name = "Stephen King"),
                    createSearchResult(asin = "B002", name = "Stephen King Jr"),
                )

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns searchResults

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // When - manual search with different query
            viewModel.updateQuery("Stephen")
            viewModel.search()
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertEquals(2, state.searchResults.size)
            assertFalse(state.isSearching)
            assertNull(state.searchError)
        }

    @Test
    fun `search handles error gracefully`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } throws
                RuntimeException("Network error")

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertFalse(state.isSearching)
            assertEquals("Network error", state.searchError)
        }

    @Test
    fun `search with blank query does nothing`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor(name = "")
            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            viewModel.updateQuery("   ") // Blank query

            // When
            viewModel.search()
            advanceUntilIdle()

            // Then - search should not be called (only the auto-search check, which is skipped for blank name)
            verifySuspend(VerifyMode.exactly(0)) {
                fixture.metadataRepository.searchContributors(any(), any())
            }
        }

    // ========== Candidate Selection Tests ==========

    @Test
    fun `selectCandidate loads profile`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val searchResult = createSearchResult()
            val profile = createProfile()

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns listOf(searchResult)
            everySuspend { fixture.metadataRepository.getContributorProfile("B001ASIN01") } returns profile

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // When
            viewModel.selectCandidate(searchResult)
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertEquals(searchResult, state.selectedCandidate)
            assertEquals(profile, state.previewProfile)
            assertFalse(state.isLoadingPreview)
            assertNull(state.previewError)
        }

    @Test
    fun `selectCandidate initializes field selections based on profile`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val searchResult = createSearchResult()
            // Profile with name and biography but no image
            val profile = createProfile(imageUrl = null)

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns listOf(searchResult)
            everySuspend { fixture.metadataRepository.getContributorProfile(any()) } returns profile

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // When
            viewModel.selectCandidate(searchResult)
            advanceUntilIdle()

            // Then - image selection should be false since no image available
            val selections = viewModel.state.value.selections
            assertTrue(selections.name)
            assertTrue(selections.biography)
            assertFalse(selections.image)
        }

    // ========== Apply Tests ==========

    @Test
    fun `apply successfully updates contributor via use case`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val searchResult = createSearchResult()
            val profile = createProfile()
            val updatedContributor = createContributor(name = "Updated Name")

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns listOf(searchResult)
            everySuspend { fixture.metadataRepository.getContributorProfile(any()) } returns profile
            everySuspend { fixture.applyContributorMetadataUseCase.invoke(any()) } returns Success(updatedContributor)

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()
            viewModel.selectCandidate(searchResult)
            advanceUntilIdle()

            // When
            viewModel.apply()
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertTrue(state.applySuccess)
            assertFalse(state.isApplying)
            assertNull(state.applyError)
            assertEquals(updatedContributor, state.currentContributor)

            // Verify use case was called
            verifySuspend(VerifyMode.exactly(1)) {
                fixture.applyContributorMetadataUseCase.invoke(any())
            }
        }

    @Test
    fun `apply handles use case failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val searchResult = createSearchResult()
            val profile = createProfile()

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns listOf(searchResult)
            everySuspend { fixture.metadataRepository.getContributorProfile(any()) } returns profile
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.applyContributorMetadataUseCase.invoke(any()) } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "Server error"))

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()
            viewModel.selectCandidate(searchResult)
            advanceUntilIdle()

            // When
            viewModel.apply()
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertFalse(state.applySuccess)
            assertFalse(state.isApplying)
            assertEquals("Server error", state.applyError)
        }

    @Test
    fun `apply does nothing when no fields selected`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val searchResult = createSearchResult()
            // Profile with nothing available
            val profile =
                ContributorMetadataProfile(
                    asin = "B001ASIN01",
                    name = "",
                    biography = null,
                    imageUrl = null,
                )

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns listOf(searchResult)
            everySuspend { fixture.metadataRepository.getContributorProfile(any()) } returns profile

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()
            viewModel.selectCandidate(searchResult)
            advanceUntilIdle()

            // When
            viewModel.apply()
            advanceUntilIdle()

            // Then - use case should not be called since no fields selected
            verifySuspend(VerifyMode.exactly(0)) {
                fixture.applyContributorMetadataUseCase.invoke(any())
            }
        }

    // ========== Field Toggle Tests ==========

    @Test
    fun `toggleField updates selections`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors(any(), any()) } returns emptyList()

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // Initial state - all true by default
            assertTrue(viewModel.state.value.selections.name)

            // When
            viewModel.toggleField(ContributorMetadataField.NAME)

            // Then
            assertFalse(viewModel.state.value.selections.name)

            // Toggle back
            viewModel.toggleField(ContributorMetadataField.NAME)
            assertTrue(viewModel.state.value.selections.name)
        }

    // ========== Region Change Tests ==========

    @Test
    fun `changeRegion updates state and re-searches`() =
        runTest {
            // Given
            val fixture = createFixture()
            val contributor = createContributor()
            val usResults = listOf(createSearchResult(name = "Stephen King US"))
            val ukResults = listOf(createSearchResult(name = "Stephen King UK"))

            every { fixture.contributorRepository.observeById("contributor-1") } returns flowOf(contributor)
            everySuspend { fixture.metadataRepository.searchContributors("Stephen King", "us") } returns usResults
            everySuspend { fixture.metadataRepository.searchContributors("Stephen King", "uk") } returns ukResults

            val viewModel = fixture.build()
            viewModel.init("contributor-1")
            advanceUntilIdle()

            // Verify initial region and results
            assertEquals(com.calypsan.listenup.client.presentation.metadata.AudibleRegion.US, viewModel.state.value.selectedRegion)
            assertEquals(usResults, viewModel.state.value.searchResults)

            // When
            viewModel.changeRegion(com.calypsan.listenup.client.presentation.metadata.AudibleRegion.UK)
            advanceUntilIdle()

            // Then
            assertEquals(com.calypsan.listenup.client.presentation.metadata.AudibleRegion.UK, viewModel.state.value.selectedRegion)
            assertEquals(ukResults, viewModel.state.value.searchResults)
        }
}
