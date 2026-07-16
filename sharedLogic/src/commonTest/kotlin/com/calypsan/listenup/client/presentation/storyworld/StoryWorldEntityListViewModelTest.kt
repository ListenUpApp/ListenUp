package com.calypsan.listenup.client.presentation.storyworld

import app.cash.turbine.test
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.test.fake.FakeEntityEditRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeWorldEventEditRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [StoryWorldEntityListViewModel]. Pins [EntityListRow.entryCount] to only
 * frontier-visible events, the [EntityKind] filter narrowing to a single group, and empty
 * kind groups being omitted entirely (unlike the hub's always-3-tile shape).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoryWorldEntityListViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun entity(
            id: String,
            name: String,
            kind: EntityKind,
        ) = Entity(id = id, kind = kind, name = name, homeSeriesId = "series-1")

        fun event(
            id: String,
            mentionIds: List<String>,
            bookId: String? = "book-1",
            positionMs: Long? = null,
        ) = WorldEvent(
            id = id,
            homeSeriesId = "series-1",
            bookId = bookId,
            positionMs = positionMs,
            type = WorldEventType.NOTE,
            text = "an event",
            mentionIds = mentionIds,
            source = WorldEventSource.MANUAL,
        )

        fun position(maxPositionMs: Long) =
            PlaybackPosition(
                bookId = "book-1",
                positionMs = 0L,
                maxPositionMs = maxPositionMs,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAtMs = 0L,
                syncedAtMs = null,
                lastPlayedAtMs = null,
                startedAtMs = 1_000L,
            )

        class Fixture {
            val entityRepo = FakeEntityEditRepository()
            val eventRepo = FakeWorldEventEditRepository()
            val positionRepo = FakePlaybackPositionRepository(mapOf("book-1" to position(maxPositionMs = 100_000L)))

            fun build() =
                StoryWorldEntityListViewModel(
                    entityEditRepository = entityRepo,
                    worldEventEditRepository = eventRepo,
                    playbackPositionRepository = positionRepo,
                )
        }

        test("entryCount counts only frontier-visible events, excluding those beyond the frontier") {
            runTest {
                val fixture = Fixture()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                        entity("loc-1", "Urithiru", EntityKind.LOCATION),
                    ),
                )
                fixture.eventRepo.setEvents(
                    listOf(
                        event("e-visible", mentionIds = listOf("char-1"), positionMs = 50_000L),
                        event("e-beyond", mentionIds = listOf("char-1"), positionMs = 500_000L),
                        event("e-baseline", mentionIds = listOf("char-2"), bookId = null, positionMs = null),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldEntityListUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldEntityListUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldEntityListUiState.Ready>()
                    val characterGroup = ready.groups.single { it.kind == EntityKind.CHARACTER }
                    characterGroup.rows.single { it.id == "char-1" }.entryCount shouldBe 1
                    characterGroup.rows.single { it.id == "char-2" }.entryCount shouldBe 1
                    val locationGroup = ready.groups.single { it.kind == EntityKind.LOCATION }
                    locationGroup.rows.single { it.id == "loc-1" }.entryCount shouldBe 0
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("kindFilter narrows to a single group") {
            runTest {
                val fixture = Fixture()
                fixture.entityRepo.setEntities(
                    listOf(
                        entity("char-1", "Kaladin", EntityKind.CHARACTER),
                        entity("char-2", "Shallan", EntityKind.CHARACTER),
                        entity("loc-1", "Urithiru", EntityKind.LOCATION),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldEntityListUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"), kindFilter = EntityKind.CHARACTER)
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldEntityListUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldEntityListUiState.Ready>()
                    ready.groups.size shouldBe 1
                    ready.groups.single().kind shouldBe EntityKind.CHARACTER
                    ready.groups
                        .single()
                        .rows
                        .map { it.id } shouldBe listOf("char-1", "char-2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("kinds with no entities are omitted, unlike the hub's always-3-tile shape") {
            runTest {
                val fixture = Fixture()
                fixture.entityRepo.setEntities(
                    listOf(entity("char-1", "Kaladin", EntityKind.CHARACTER)),
                    // No LOCATION or ITEM entities at all.
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe StoryWorldEntityListUiState.Idle
                    viewModel.load(WorldRef(seriesId = "series-1"))
                    advanceUntilIdle()
                    awaitItem() shouldBe StoryWorldEntityListUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<StoryWorldEntityListUiState.Ready>()
                    ready.groups.map { it.kind } shouldBe listOf(EntityKind.CHARACTER)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
