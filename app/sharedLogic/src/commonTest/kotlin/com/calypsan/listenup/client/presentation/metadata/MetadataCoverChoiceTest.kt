package com.calypsan.listenup.client.presentation.metadata

import kotlinx.coroutines.flow.flowOf
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.api.dto.MatchProvenance
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val AUDIBLE_COVER = "https://audible/cover.jpg"
private const val ITUNES_COVER = "https://itunes/cover-3000.jpg"

/**
 * The cover the preview shows as chosen is exactly the cover Apply writes — including "keep the
 * current one", which is its own explicit choice rather than a null the server reads as "take the
 * match's biggest cover".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetadataCoverChoiceTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun match(
            coverUrl: String? = AUDIBLE_COVER,
            coverUrlMaxSize: String? = ITUNES_COVER,
        ) = MetadataBook(
            asin = "B001",
            title = "Dune",
            subtitle = null,
            description = null,
            publisher = null,
            releaseDate = null,
            runtimeMinutes = null,
            language = null,
            authors = listOf(MetadataContributorRef(asin = "A1", name = "Frank Herbert")),
            narrators = emptyList(),
            series = emptyList(),
            genres = emptyList(),
            coverUrl = coverUrl,
            coverUrlMaxSize = coverUrlMaxSize,
            matchProvenance =
                MatchProvenance(
                    contributingSources = listOf("Audible", "iTunes"),
                    coverSource = "iTunes",
                    coverWidth = 3000,
                    coverHeight = 3000,
                ),
        )

        class Rig(
            val vm: MetadataViewModel,
            val applied: () -> MetadataApplySelection?,
        )

        suspend fun TestScope.readyRig(book: MetadataBook = match()): Rig {
            var applied: MetadataApplySelection? = null
            val repo = mock<MetadataRepository>()
            everySuspend { repo.getBookMetadata(any(), any(), any()) } returns AppResult.Success(book)
            everySuspend { repo.getBookChapters(any(), any()) } returns AppResult.Success(MetadataChapters(emptyList()))
            everySuspend { repo.applyBookMetadata(any(), any(), any(), any()) } calls { args ->
                applied = args.arg(3)
                AppResult.Success(Unit)
            }
            val vm =
                MetadataViewModel(
                    metadataRepository = repo,
                    bookRepository = mock<BookRepository> { everySuspend { getChapters(any()) } returns emptyList() },
                    genreRepository = mock<GenreRepository> { everySuspend { getGenresForBook(any()) } returns emptyList() },
                    moodRepository = mock<MoodRepository> { every { observeMoodsForBook(any()) } returns MutableStateFlow(emptyList()) },
                    tagRepository = mock<TagRepository> { every { observeTagsForBook(any()) } returns MutableStateFlow(emptyList()) },
                    errorBus = ErrorBus(),
                    libraryRepository = mock<LibraryRepository> { every { observeAll() } returns flowOf(emptyList()) },
                )
            vm.initForBook("b1", "Dune", "Frank Herbert")
            vm.selectMatch(book)
            advanceUntilIdle()
            return Rig(vm) { applied }
        }

        fun MetadataViewModel.ready(): PreviewLoadState.Ready = (state.value as MetadataUiState.Preview).loadState as PreviewLoadState.Ready

        suspend fun TestScope.applyAndCapture(rig: Rig): MetadataApplySelection {
            rig.vm.applyMatch()
            advanceUntilIdle()
            return rig.applied().shouldNotBeNull()
        }

        test("the default choice is the match's best cover, shown as chosen and named by its real source") {
            runTest {
                val rig = readyRig()
                val ready = rig.vm.ready()

                ready.keepsCurrentCover shouldBe false
                ready.appliedCover.shouldNotBeNull().url shouldBe ITUNES_COVER
                ready.appliedCover?.label shouldBe "iTunes"

                val sent = applyAndCapture(rig)
                sent.cover shouldBe true
                sent.coverUrl shouldBe ITUNES_COVER
            }
        }

        test("keeping the current cover sends no cover at all") {
            runTest {
                val rig = readyRig()
                rig.vm.keepCurrentCover()

                rig.vm.ready().keepsCurrentCover shouldBe true
                rig.vm
                    .ready()
                    .appliedCover
                    .shouldBeNull()

                val sent = applyAndCapture(rig)
                sent.cover shouldBe false
                sent.coverUrl.shouldBeNull()
            }
        }

        test("choosing a specific candidate applies exactly that URL") {
            runTest {
                val rig = readyRig()
                rig.vm.keepCurrentCover()
                rig.vm.selectCover(AUDIBLE_COVER)

                rig.vm
                    .ready()
                    .appliedCover
                    ?.label shouldBe "Audible"

                val sent = applyAndCapture(rig)
                sent.cover shouldBe true
                sent.coverUrl shouldBe AUDIBLE_COVER
            }
        }

        test("a URL that is not one of the shown candidates cannot be chosen") {
            runTest {
                val rig = readyRig()
                rig.vm.selectCover("https://elsewhere/not-shown.jpg")

                applyAndCapture(rig).coverUrl shouldBe ITUNES_COVER
            }
        }

        test("unticking Cover keeps the current one; ticking it again restores the chosen candidate") {
            runTest {
                val rig = readyRig()
                rig.vm.selectCover(AUDIBLE_COVER)

                rig.vm.toggleField(MetadataField.COVER)
                rig.vm.ready().keepsCurrentCover shouldBe true

                rig.vm.toggleField(MetadataField.COVER)
                rig.vm
                    .ready()
                    .appliedCover
                    ?.url shouldBe AUDIBLE_COVER
                applyAndCapture(rig).coverUrl shouldBe AUDIBLE_COVER
            }
        }

        test("a match with no artwork keeps the current cover, and ticking Cover cannot change that") {
            runTest {
                val rig = readyRig(match(coverUrl = null, coverUrlMaxSize = null))
                rig.vm.ready().keepsCurrentCover shouldBe true

                rig.vm.toggleField(MetadataField.COVER)
                rig.vm.ready().keepsCurrentCover shouldBe true

                val sent = applyAndCapture(rig)
                sent.cover shouldBe false
                sent.coverUrl.shouldBeNull()
            }
        }
    })
