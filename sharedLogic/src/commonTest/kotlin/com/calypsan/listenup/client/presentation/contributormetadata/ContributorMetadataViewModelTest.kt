package com.calypsan.listenup.client.presentation.contributormetadata

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for the rebuilt [ContributorMetadataViewModel] — sealed state, timeout,
 * stale-result guards, honest-miss preview, and one-shot apply event.
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

        fun contributorRepoWith(contributor: Contributor?): ContributorRepository =
            mock<ContributorRepository> {
                every { observeById(any()) } returns MutableStateFlow(contributor)
            }

        fun buildVm(
            metadataRepo: MetadataRepository = mock(),
            contributorRepo: ContributorRepository = contributorRepoWith(createContributor()),
        ): ContributorMetadataViewModel =
            ContributorMetadataViewModel(
                contributorRepository = contributorRepo,
                metadataRepository = metadataRepo,
                errorBus = ErrorBus(),
            )

        // ── init ───────────────────────────────────────────────────────────────

        test("init enters Search synchronously, then seeds query with the contributor's name and auto-searches") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Success(listOf(createHit()))
                val vm = buildVm(metadataRepo)

                vm.init("contributor-1")
                // Synchronous phase: Search entered before any coroutine runs.
                vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()

                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()
                state.context.contributorId shouldBe "contributor-1"
                state.context.current?.name shouldBe "Stephen King"
                state.query shouldBe "Stephen King"
                state.loadState.shouldBeInstanceOf<ContributorSearchLoadState.Loaded>()
                verifySuspend { metadataRepo.searchContributorMetadata("Stephen King", MetadataLocale.DEFAULT) }
            }
        }

        // ── search ─────────────────────────────────────────────────────────────

        test("search passes the selected region and lands in Loaded") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Success(listOf(createHit()))
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.changeRegion(MetadataLocale("de"))
                advanceUntilIdle()

                verifySuspend { metadataRepo.searchContributorMetadata("Stephen King", MetadataLocale("de")) }
                val state = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()
                state.region shouldBe MetadataLocale("de")
            }
        }

        test("search failure lands in Failed with the error's message") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val error = TransportError.NetworkUnavailable()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns AppResult.Failure(error)
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()
                val failed = state.loadState.shouldBeInstanceOf<ContributorSearchLoadState.Failed>()
                failed.message shouldBe error.message
            }
        }

        test("search that never resolves surfaces Failed after the timeout, not an infinite spinner") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } calls { awaitCancellation() }
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()
                state.loadState.shouldBeInstanceOf<ContributorSearchLoadState.Failed>()
            }
        }

        // ── selectCandidate / preview ──────────────────────────────────────────

        test("selectCandidate transitions to Preview.Ready when the profile has data") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Success(listOf(createHit()))
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                ready.profile.asin shouldBe "B001ASIN01"
            }
        }

        test("an empty-shell profile (no bio, no image) lands in Missing, not Ready") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile(description = null, imageUrl = null))
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.loadState shouldBe ContributorPreviewLoadState.Missing
            }
        }

        test("a null profile (catalog 404) lands in Missing") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns AppResult.Success(null)
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.loadState shouldBe ContributorPreviewLoadState.Missing
            }
        }

        test("a bio-only profile (no photo) lands in Ready, not Missing") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile(description = "Has a biography.", imageUrl = null))
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
            }
        }

        test("a photo-only profile (no bio) lands in Ready, not Missing") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile(description = null, imageUrl = "https://example.com/photo.jpg"))
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
            }
        }

        test("two rapid selectCandidate calls: only the second candidate's profile lands") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata("ASIN-A", any()) } returns
                    AppResult.Success(createProfile(asin = "ASIN-A", name = "First"))
                everySuspend { metadataRepo.getContributorMetadata("ASIN-B", any()) } returns
                    AppResult.Success(createProfile(asin = "ASIN-B", name = "Second"))
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit(asin = "ASIN-A", name = "First"))
                vm.selectCandidate(createHit(asin = "ASIN-B", name = "Second"))
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.match.asin shouldBe "ASIN-B"
                val ready = preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                // The displayed profile can never diverge from the match ASIN apply() will send.
                ready.profile.asin shouldBe "ASIN-B"
            }
        }

        test("preview that never resolves surfaces Failed after the timeout") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } calls { awaitCancellation() }
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Failed>()
            }
        }

        test("changeRegion in preview re-fetches the open profile in the new region") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.changeRegion(MetadataLocale("uk"))
                advanceUntilIdle()

                verifySuspend { metadataRepo.getContributorMetadata("B001ASIN01", MetadataLocale("uk")) }
                vm.state.value.region shouldBe MetadataLocale("uk")
            }
        }

        test("changeRegion in preview atomically resets to Loading — never a stale Ready visible mid-transition") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.changeRegion(MetadataLocale("uk"))

                // Asserted BEFORE advanceUntilIdle(): region and loadState must land together, in the
                // same state.update — never a moment where the new region pairs with the old Ready profile.
                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.region shouldBe MetadataLocale("uk")
                preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Loading>()
            }
        }

        test("clearSelection returns to Search with retained results — a late profile fetch cannot resurrect the preview") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Success(listOf(createHit()))
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectCandidate(createHit())
                // Clear BEFORE the profile fetch completes.
                vm.clearSelection()
                advanceUntilIdle()

                val state = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()
                val loaded = state.loadState.shouldBeInstanceOf<ContributorSearchLoadState.Loaded>()
                loaded.results.size shouldBe 1
            }
        }

        // ── apply ──────────────────────────────────────────────────────────────

        test("apply success emits a one-shot MetadataApplied event") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { metadataRepo.applyContributorMetadata(any(), any(), any()) } returns
                    AppResult.Success(Unit)
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.events.test {
                    vm.apply()
                    advanceUntilIdle()
                    awaitItem() shouldBe ContributorMetadataEvent.MetadataApplied
                }
                verifySuspend {
                    metadataRepo.applyContributorMetadata(
                        ContributorId("contributor-1"),
                        "B001ASIN01",
                        MetadataLocale.DEFAULT,
                    )
                }
            }
        }

        test("apply failure overlays applyError on the Ready preview and stays Ready") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                val error = TransportError.NetworkUnavailable()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { metadataRepo.applyContributorMetadata(any(), any(), any()) } returns
                    AppResult.Failure(error)
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.apply()
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                ready.isApplying shouldBe false
                ready.applyError shouldBe error.message
            }
        }

        test("apply is a no-op outside Ready (Missing preview has no live apply path)") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns AppResult.Success(null)
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                vm.apply()
                advanceUntilIdle()

                verifySuspend(mode = dev.mokkery.verify.VerifyMode.exactly(0)) {
                    metadataRepo.applyContributorMetadata(any(), any(), any())
                }
            }
        }

        test("apply racing against clearSelection: late apply outcome does not fire MetadataApplied or resurrect Preview") {
            runTest {
                val applyDeferred = CompletableDeferred<AppResult<Unit>>()
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Success(listOf(createHit()))
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { metadataRepo.applyContributorMetadata(any(), any(), any()) } calls { applyDeferred.await() }
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                // Apply is in flight; the user abandons it by clearing the selection before it resolves.
                vm.apply()
                vm.clearSelection()

                vm.events.test {
                    applyDeferred.complete(AppResult.Success(Unit))
                    advanceUntilIdle()
                    expectNoEvents()
                }

                // Still Search — the late apply success must not resurrect the abandoned Preview.
                vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Search>()
            }
        }

        test("apply racing against changeRegion: late apply outcome does not fire MetadataApplied for the abandoned region") {
            runTest {
                val applyDeferred = CompletableDeferred<AppResult<Unit>>()
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { metadataRepo.applyContributorMetadata(any(), any(), any()) } calls { applyDeferred.await() }
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                // Apply targets the DEFAULT region; the user switches region before it resolves.
                vm.apply()
                vm.changeRegion(MetadataLocale("uk"))
                advanceUntilIdle() // the new-region profile fetch resolves; apply is still pending

                vm.events.test {
                    applyDeferred.complete(AppResult.Success(Unit))
                    advanceUntilIdle()
                    expectNoEvents()
                }

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.region shouldBe MetadataLocale("uk")
                val ready = preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                ready.isApplying shouldBe false
                ready.applyError shouldBe null
            }
        }

        test(
            "apply racing against selectCandidate: stale apply outcome fires no event and does not overlay onto the newly selected candidate",
        ) {
            runTest {
                val applyDeferred = CompletableDeferred<AppResult<Unit>>()
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata("ASIN-A", any()) } returns
                    AppResult.Success(createProfile(asin = "ASIN-A", name = "First"))
                everySuspend { metadataRepo.getContributorMetadata("ASIN-B", any()) } returns
                    AppResult.Success(createProfile(asin = "ASIN-B", name = "Second"))
                everySuspend { metadataRepo.applyContributorMetadata(any(), any(), any()) } calls { applyDeferred.await() }
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit(asin = "ASIN-A", name = "First"))
                advanceUntilIdle()

                // Apply targets ASIN-A; the user picks a different candidate before it resolves —
                // ASIN-B's Ready lands first, THEN the stale ASIN-A apply outcome arrives.
                vm.apply()
                vm.selectCandidate(createHit(asin = "ASIN-B", name = "Second"))
                advanceUntilIdle()

                vm.events.test {
                    applyDeferred.complete(AppResult.Failure(TransportError.NetworkUnavailable()))
                    advanceUntilIdle()
                    expectNoEvents()
                }

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.match.asin shouldBe "ASIN-B"
                val ready = preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                // The stale ASIN-A failure must not overlay an error onto the currently-viewed ASIN-B profile.
                ready.applyError shouldBe null
                ready.isApplying shouldBe false
            }
        }

        test(
            "apply racing against a fresh re-select of the SAME candidate: the abandoned attempt's late outcome " +
                "does not fire MetadataApplied — identity alone cannot distinguish two attempts at the same target",
        ) {
            runTest {
                val applyDeferred = CompletableDeferred<AppResult<Unit>>()
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.searchContributorMetadata(any(), any()) } returns
                    AppResult.Success(listOf(createHit()))
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile())
                everySuspend { metadataRepo.applyContributorMetadata(any(), any(), any()) } calls { applyDeferred.await() }
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                // Step 1: apply on candidate A (default region), left in flight.
                vm.apply()

                // Step 2: abandon it by returning to search.
                vm.clearSelection()

                // Step 3: re-select the SAME candidate, same region — a fresh Ready, isApplying=false.
                // Its identity (contributorId, asin, region) is IDENTICAL to the abandoned attempt's.
                vm.selectCandidate(createHit())
                advanceUntilIdle()

                val freshReady =
                    vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                        .loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                freshReady.isApplying shouldBe false
                freshReady.applyError shouldBe null

                // Step 4: the ABANDONED apply from step 1 resolves late. Same identity as the fresh
                // state, but a DIFFERENT attempt — must not fire the event or overlay the fresh Ready.
                vm.events.test {
                    applyDeferred.complete(AppResult.Success(Unit))
                    advanceUntilIdle()
                    expectNoEvents()
                }

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                val ready = preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
                ready.isApplying shouldBe false
                ready.applyError shouldBe null
            }
        }

        // ── selectAsin (route entry) ───────────────────────────────────────────

        test("selectAsin fetches by ASIN and backfills the match name from the profile") {
            runTest {
                val metadataRepo = mock<MetadataRepository>()
                everySuspend { metadataRepo.getContributorMetadata(any(), any()) } returns
                    AppResult.Success(createProfile(name = "Backfilled Name"))
                val vm = buildVm(metadataRepo)
                vm.init("contributor-1")
                advanceUntilIdle()

                vm.selectAsin("B001ASIN01")
                advanceUntilIdle()

                val preview = vm.state.value.shouldBeInstanceOf<ContributorMetadataUiState.Preview>()
                preview.match.name shouldBe "Backfilled Name"
                preview.loadState.shouldBeInstanceOf<ContributorPreviewLoadState.Ready>()
            }
        }
    })
