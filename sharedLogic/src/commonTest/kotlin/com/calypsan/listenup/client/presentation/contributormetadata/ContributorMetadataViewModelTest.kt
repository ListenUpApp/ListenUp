package com.calypsan.listenup.client.presentation.contributormetadata

import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ContributorMetadataViewModel].
 *
 * Uses `:contract` DTOs ([MetadataContributorHit], [MetadataContributorProfile])
 * directly — legacy domain `ContributorMetadataCandidate` / `ContributorMetadataProfile`
 * have been removed in B2b.
 *
 * Note: [MetadataContributorProfile.description] is the biography field.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorMetadataViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ── Test data factories ────────────────────────────────────────────────

        fun createContributor(
            id: String = "contributor-1",
            name: String = "Stephen King",
        ): Contributor =
            Contributor(
                id = ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
            )

        fun createHit(
            asin: String = "B001ASIN01",
            name: String = "Stephen King",
        ): MetadataContributorHit = MetadataContributorHit(asin = asin, name = name)

        fun createProfile(
            asin: String = "B001ASIN01",
            name: String = "Stephen King",
            description: String? = "Stephen King is a prolific author...",
            imageUrl: String? = "https://example.com/image.jpg",
        ): MetadataContributorProfile =
            MetadataContributorProfile(
                asin = asin,
                name = name,
                sortName = null,
                description = description,
                imageUrl = imageUrl,
                birthDate = null,
                deathDate = null,
                website = null,
            )

        fun buildVm(
            contributorRepo: ContributorRepository = mock(),
            metadataRepo: MetadataRepository = mock(),
            useCase: ApplyContributorMetadataUseCase = mock(),
        ): ContributorMetadataViewModel =
            ContributorMetadataViewModel(
                contributorRepository = contributorRepo,
                metadataRepository = metadataRepo,
                applyContributorMetadataUseCase = useCase,
                errorBus = ErrorBus(),
            )

        // ── Initialization Tests ───────────────────────────────────────────────

        test("init synchronously resets state to prevent stale state bugs") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val contributor = createContributor(id = "contributor-2", name = "Neil Gaiman")
                // Use MutableStateFlow per memory [test-stateflow-use-mutablestateflow]
                every { contributorRepo.observeById("contributor-2") } returns MutableStateFlow(contributor)
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns AppResult.Success(emptyList())

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo)
                vm.init("contributor-2")

                // Synchronous reset happens before advanceUntilIdle
                val stateBeforeAsync = vm.state.value
                stateBeforeAsync.contributorId shouldBe "contributor-2"
                stateBeforeAsync.applySuccess shouldBe false
                stateBeforeAsync.currentContributor shouldBe null
                stateBeforeAsync.searchQuery shouldBe ""
                stateBeforeAsync.searchResults.isEmpty() shouldBe true
            }
        }

        test("init loads contributor and pre-fills search query") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val contributor = createContributor(name = "Stephen King")
                every { contributorRepo.observeById("contributor-1") } returns MutableStateFlow(contributor)
                everySuspend { metadataRepo.searchContributorMetadata("Stephen King", any()) } returns
                    AppResult.Success(listOf(createHit()))

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.state.value.contributorId shouldBe "contributor-1"
                vm.state.value.currentContributor shouldBe contributor
                vm.state.value.searchQuery shouldBe "Stephen King"
            }
        }

        test("init auto-searches with contributor name") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val contributor = createContributor(name = "Stephen King")
                val searchResults = listOf(createHit())
                every { contributorRepo.observeById("contributor-1") } returns MutableStateFlow(contributor)
                everySuspend { metadataRepo.searchContributorMetadata("Stephen King", any()) } returns
                    AppResult.Success(searchResults)

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.state.value.searchResults shouldBe searchResults
                verifySuspend(VerifyMode.exactly(1)) {
                    metadataRepo.searchContributorMetadata("Stephen King", any())
                }
            }
        }

        test("init does not auto-search when contributor name is blank") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val contributor = createContributor(name = "")
                every { contributorRepo.observeById("contributor-1") } returns MutableStateFlow(contributor)

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(0)) { metadataRepo.searchContributorMetadata(any(), any()) }
            }
        }

        // ── Search Tests ───────────────────────────────────────────────────────

        test("search updates state with results") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val results = listOf(createHit("B001", "Stephen King"), createHit("B002", "Stephen King Jr"))
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns AppResult.Success(results)

                val vm = buildVm(metadataRepo = metadataRepo)
                vm.state.value.let {} // ensure initial state
                vm.updateQuery("Stephen King")
                vm.search()
                advanceUntilIdle()

                vm.state.value.searchResults.size shouldBe 2
                vm.state.value.isSearching shouldBe false
                vm.state.value.searchError shouldBe null
            }
        }

        test("search handles failure and sets searchError") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "Network error."))

                val vm = buildVm(metadataRepo = metadataRepo)
                vm.updateQuery("Stephen King")
                vm.search()
                advanceUntilIdle()

                vm.state.value.isSearching shouldBe false
                vm.state.value.searchError shouldBe "Network error."
            }
        }

        test("search with blank query does nothing") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()

                val vm = buildVm(metadataRepo = metadataRepo)
                vm.updateQuery("   ")
                vm.search()
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(0)) { metadataRepo.searchContributorMetadata(any(), any()) }
            }
        }

        // ── Candidate Selection Tests ──────────────────────────────────────────

        test("selectCandidate loads profile and updates state") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val hit = createHit()
                val profile = createProfile()
                everySuspend { metadataRepo.getContributorMetadata("B001ASIN01", any()) } returns
                    AppResult.Success(profile)

                val vm = buildVm(metadataRepo = metadataRepo)
                vm.selectCandidate(hit)
                advanceUntilIdle()

                vm.state.value.selectedCandidate shouldBe hit
                vm.state.value.previewProfile shouldBe profile
                vm.state.value.isLoadingPreview shouldBe false
                vm.state.value.previewError shouldBe null
            }
        }

        test("selectCandidate initializes selections — no image when imageUrl null") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val hit = createHit()
                val profile = createProfile(imageUrl = null)
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(profile)

                val vm = buildVm(metadataRepo = metadataRepo)
                vm.selectCandidate(hit)
                advanceUntilIdle()

                vm.state.value.selections.name shouldBe true
                vm.state.value.selections.biography shouldBe true
                vm.state.value.selections.image shouldBe false
            }
        }

        test("selectCandidate null profile sets previewError") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val hit = createHit()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(null)

                val vm = buildVm(metadataRepo = metadataRepo)
                vm.selectCandidate(hit)
                advanceUntilIdle()

                vm.state.value.isLoadingPreview shouldBe false
                vm.state.value.previewError shouldBe "No profile found on Audible."
            }
        }

        // ── Apply Tests ────────────────────────────────────────────────────────

        test("apply sets applySuccess when use case succeeds") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val useCase = mock<ApplyContributorMetadataUseCase>()
                val contributor = createContributor()
                every { contributorRepo.observeById("contributor-1") } returns MutableStateFlow(contributor)
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns AppResult.Success(emptyList())
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { useCase.invoke(any()) } returns AppResult.Success(Unit)

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo, useCase = useCase)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.apply()
                advanceUntilIdle()

                vm.state.value.applySuccess shouldBe true
                vm.state.value.isApplying shouldBe false
                vm.state.value.applyError shouldBe null
            }
        }

        test("apply handles use case failure and sets applyError") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val useCase = mock<ApplyContributorMetadataUseCase>()
                val contributor = createContributor()
                every { contributorRepo.observeById("contributor-1") } returns MutableStateFlow(contributor)
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns AppResult.Success(emptyList())
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { useCase.invoke(any()) } returns
                    AppResult.Failure(ValidationError(message = "Server error."))

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo, useCase = useCase)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.apply()
                advanceUntilIdle()

                vm.state.value.applySuccess shouldBe false
                vm.state.value.isApplying shouldBe false
                vm.state.value.applyError shouldBe "Server error."
            }
        }

        test("apply does nothing when no fields selected") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val useCase = mock<ApplyContributorMetadataUseCase>()
                // Profile with no data — all selections false
                val emptyProfile = createProfile(name = "", description = null, imageUrl = null)
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(emptyProfile)

                val vm = buildVm(metadataRepo = metadataRepo, useCase = useCase)
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.apply()
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(0)) { useCase.invoke(any()) }
            }
        }

        // ── Field Toggle Tests ─────────────────────────────────────────────────

        test("toggleField updates selections") {
            runTest {
                val vm = buildVm()
                vm.state.value.selections.name shouldBe true

                vm.toggleField(ContributorMetadataField.NAME)
                vm.state.value.selections.name shouldBe false

                vm.toggleField(ContributorMetadataField.NAME)
                vm.state.value.selections.name shouldBe true
            }
        }

        // ── Region Change Tests ────────────────────────────────────────────────

        test("changeRegion updates state and re-searches") {
            runTest {
                val contributorRepo = mock<ContributorRepository>()
                val metadataRepo = mock<MetadataRepository>()
                val contributor = createContributor()
                val usResults = listOf(createHit(name = "Stephen King US"))
                val ukResults = listOf(createHit(name = "Stephen King UK"))
                every { contributorRepo.observeById("contributor-1") } returns MutableStateFlow(contributor)
                everySuspend { metadataRepo.searchContributorMetadata("Stephen King", any()) } returns
                    AppResult.Success(usResults)

                val vm = buildVm(contributorRepo = contributorRepo, metadataRepo = metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.state.value.selectedRegion shouldBe MetadataLocale.DEFAULT
                vm.state.value.searchResults shouldBe usResults

                // Update mock for UK re-search
                everySuspend { metadataRepo.searchContributorMetadata("Stephen King", any()) } returns
                    AppResult.Success(ukResults)
                vm.changeRegion(MetadataLocale("uk"))
                advanceUntilIdle()

                vm.state.value.selectedRegion shouldBe MetadataLocale("uk")
                vm.state.value.searchResults shouldBe ukResults
            }
        }
    })
