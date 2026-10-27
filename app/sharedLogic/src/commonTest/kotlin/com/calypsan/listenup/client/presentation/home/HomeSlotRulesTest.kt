package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.Reachability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val NOW = 1_800_000_000_000L
private const val HOUR = 3_600_000L

class HomeSlotRulesTest :
    FunSpec({
        context("precedence (spec §2.2)") {
            test("nothing loaded and a book in progress: Resume the most recent ready book") {
                slotKindFor(inputs(cl = listOf(ready("a"), ready("b")), hasHistory = true)) shouldBe SlotKind.Resume("a")
            }

            test("nothing loaded, nothing in progress, but history: NothingInProgress") {
                slotKindFor(inputs(hasHistory = true)) shouldBe SlotKind.NothingInProgress
            }

            test("nothing loaded and no history at all: NewListener") {
                slotKindFor(inputs()) shouldBe SlotKind.NewListener
            }

            test("a loaded book: Absent, though others are in progress") {
                slotKindFor(inputs(loaded = loaded("a"), cl = listOf(ready("a"), ready("b")), hasHistory = true)) shouldBe
                    SlotKind.Absent
            }

            test("a just-finished book wins over a loaded book") {
                slotKindFor(inputs(loaded = loaded("x"), justFinished = "done", hasHistory = true)) shouldBe
                    SlotKind.JustFinished("done")
            }

            test("a just-finished book wins over Resume") {
                slotKindFor(inputs(cl = listOf(ready("a")), justFinished = "done", hasHistory = true)) shouldBe
                    SlotKind.JustFinished("done")
            }

            test("a loaded finished book with nothing else in progress: Absent") {
                slotKindFor(inputs(loaded = loaded("a", isFinished = true), hasHistory = true)) shouldBe SlotKind.Absent
            }

            test("first sync with nothing loaded and nothing yet: FirstSync, ahead of a just-finished card") {
                slotKindFor(inputs(justFinished = "done", building = true)) shouldBe SlotKind.FirstSync
            }

            test("first sync whose most recent book is still arriving: FirstSync") {
                slotKindFor(inputs(cl = listOf(loading("a"), ready("b")), building = true, hasHistory = true)) shouldBe
                    SlotKind.FirstSync
            }

            test("first sync once a ready book leads: Resume (FirstSync vs Resume)") {
                slotKindFor(inputs(cl = listOf(ready("a")), building = true, hasHistory = true)) shouldBe SlotKind.Resume("a")
            }

            test("first sync with a book loaded: Absent, not FirstSync") {
                slotKindFor(inputs(loaded = loaded("a"), building = true)) shouldBe SlotKind.Absent
            }

            test("after first sync a leading book still arriving is passed over for the first ready one (C13)") {
                slotKindFor(inputs(cl = listOf(loading("a"), ready("b")), hasHistory = true)) shouldBe SlotKind.Resume("b")
            }
        }

        context("the just-finished pick (spec §2.2 row 2)") {
            test("71 hours after the finish it shows; 73 hours after, it does not") {
                justFinishedBookId(listOf(finished("a", hoursAgo = 71)), emptySet(), emptyMap(), null, NOW) shouldBe "a"
                justFinishedBookId(listOf(finished("a", hoursAgo = 73)), emptySet(), emptyMap(), null, NOW).shouldBeNull()
            }

            test("a dismissal at or after the finish hides it; one from an earlier read does not") {
                val finishedAt = NOW - 2 * HOUR
                justFinishedBookId(listOf(finished("a", 2, dismissedAtMs = finishedAt)), emptySet(), emptyMap(), null, NOW)
                    .shouldBeNull()
                justFinishedBookId(listOf(finished("a", 2, dismissedAtMs = finishedAt - 1)), emptySet(), emptyMap(), null, NOW) shouldBe
                    "a"
            }

            test("a dismissal made here this visit hides it before Room says so") {
                justFinishedBookId(listOf(finished("a", 2)), emptySet(), mapOf("a" to NOW - 2 * HOUR), null, NOW).shouldBeNull()
            }

            test("a dismissal made here for an earlier finish does not hide a re-read") {
                justFinishedBookId(listOf(finished("a", 2)), emptySet(), mapOf("a" to NOW - 100 * HOUR), null, NOW) shouldBe "a"
            }

            test("another book played after the finish means the reader moved on; one played before does not") {
                val after = position("b", lastPlayedAtMs = NOW - 1 * HOUR)
                val before = position("b", lastPlayedAtMs = NOW - 3 * HOUR)
                justFinishedBookId(listOf(finished("a", 2), after), emptySet(), emptyMap(), null, NOW).shouldBeNull()
                justFinishedBookId(listOf(finished("a", 2), before), emptySet(), emptyMap(), null, NOW) shouldBe "a"
            }

            test("a book this visit watched being loaded after the finish means the reader moved on (C10)") {
                justFinishedBookId(listOf(finished("a", 2)), emptySet(), emptyMap(), FreshLoad("b", NOW), NOW).shouldBeNull()
            }

            test("loading the finished book itself, or a load from before the finish, is not moving on") {
                justFinishedBookId(listOf(finished("a", 2)), emptySet(), emptyMap(), FreshLoad("a", NOW), NOW) shouldBe "a"
                justFinishedBookId(listOf(finished("a", 2)), emptySet(), emptyMap(), FreshLoad("b", NOW - 3 * HOUR), NOW) shouldBe
                    "a"
            }

            test("a legacy row with no lastPlayedAt never counts as moving on") {
                val legacy = position("b", lastPlayedAtMs = null).copy(updatedAtMs = NOW)
                justFinishedBookId(listOf(finished("a", 2), legacy), emptySet(), emptyMap(), null, NOW) shouldBe "a"
            }

            test("a held book never gets a card") {
                justFinishedBookId(listOf(finished("a", 2)), setOf("a"), emptyMap(), null, NOW).shouldBeNull()
            }

            test("the newest finish wins") {
                justFinishedBookId(listOf(finished("old", 30), finished("new", 5)), emptySet(), emptyMap(), null, NOW) shouldBe "new"
            }
        }

        context("the latch (spec §2.1)") {
            val point = DecisionPoint(runs = 0, loaded = LoadedKey.NothingLoaded)

            test("the first decision takes the fresh kind") {
                latch(null, point, SlotKind.Resume("a")) shouldBe LatchedSlot(point, SlotKind.Resume("a"))
            }

            test("a position tick, a sync landing or the 72-hour expiry does not change the kind") {
                val held = LatchedSlot(point, SlotKind.JustFinished("a"))
                latch(held, point, SlotKind.Resume("b")) shouldBe held
                latch(held, point, SlotKind.NothingInProgress) shouldBe held
            }

            test("a refresh or a dismissal (a new run) re-decides") {
                val held = LatchedSlot(point, SlotKind.JustFinished("a"))
                val next = point.copy(runs = 1)
                latch(held, next, SlotKind.Resume("b")) shouldBe LatchedSlot(next, SlotKind.Resume("b"))
            }

            test("a loaded-book change re-decides") {
                val held = LatchedSlot(point, SlotKind.Resume("a"))
                val next = point.copy(loaded = LoadedKey.Loaded("a", isFinished = false))
                latch(held, next, SlotKind.Absent) shouldBe LatchedSlot(next, SlotKind.Absent)
            }

            test("play and pause are not loaded-book changes (P-R6)") {
                LoadedBook.Loaded("a", isPlaying = true, isFinished = false).toKey() shouldBe
                    LoadedBook.Loaded("a", isPlaying = false, isFinished = false).toKey()
            }

            test("finishing the loaded book is a loaded-book change") {
                (
                    LoadedBook.Loaded("a", isPlaying = false, isFinished = true).toKey() ==
                        LoadedBook.Loaded("a", isPlaying = false, isFinished = false).toKey()
                ) shouldBe false
            }

            test("a FirstSync skeleton resolves in place (rule 4)") {
                val held = LatchedSlot(point, SlotKind.FirstSync)
                latch(held, point, SlotKind.Resume("a")) shouldBe LatchedSlot(point, SlotKind.Resume("a"))
            }

            test("any playback position ends the welcome: a new listener resolves in place when history arrives") {
                val held = LatchedSlot(point, SlotKind.NewListener)
                latch(held, point, SlotKind.Resume("a")) shouldBe LatchedSlot(point, SlotKind.Resume("a"))
                latch(held, point, SlotKind.NothingInProgress) shouldBe LatchedSlot(point, SlotKind.NothingInProgress)
            }

            test("a decision made while the leading book was still arriving resolves in place too") {
                val held = LatchedSlot(point, SlotKind.NothingInProgress, isProvisional = true)
                latch(held, point, SlotKind.Resume("a")) shouldBe LatchedSlot(point, SlotKind.Resume("a"))
            }

            test("a decision is provisional only while the leading book is still arriving") {
                latch(previous = null, point = point, fresh = SlotKind.NothingInProgress, isProvisional = true).isProvisional shouldBe true
                isStillArriving(listOf(loading("a"), ready("b"))) shouldBe true
                isStillArriving(listOf(ready("a"), loading("b"))) shouldBe false
                isStillArriving(emptyList()) shouldBe false
            }
        }

        context("the loaded view") {
            test("restoring has no view") {
                nextLoadedView(null, LoadedKey.Restoring, NOW).shouldBeNull()
            }

            test("the first resolved value is a restore, never a fresh load") {
                nextLoadedView(null, LoadedKey.Loaded("a", false), NOW) shouldBe LoadedView(LoadedKey.Loaded("a", false), null)
            }

            test("loading a different book later is a fresh load, now") {
                val before = LoadedView(LoadedKey.NothingLoaded, null)
                nextLoadedView(before, LoadedKey.Loaded("b", false), NOW) shouldBe
                    LoadedView(LoadedKey.Loaded("b", false), FreshLoad("b", NOW))
            }

            test("finishing the same book keeps its fresh load") {
                val before = LoadedView(LoadedKey.Loaded("b", false), FreshLoad("b", NOW - HOUR))
                nextLoadedView(before, LoadedKey.Loaded("b", true), NOW) shouldBe
                    LoadedView(LoadedKey.Loaded("b", true), FreshLoad("b", NOW - HOUR))
            }

            test("closing the book drops the fresh load") {
                val before = LoadedView(LoadedKey.Loaded("b", false), FreshLoad("b", NOW))
                nextLoadedView(before, LoadedKey.NothingLoaded, NOW) shouldBe LoadedView(LoadedKey.NothingLoaded, null)
            }
        }

        context("Everything in progress") {
            val cl = listOf(ready("a"), ready("b"), ready("c"))

            test("puts the loaded book first and keeps the rest in their order") {
                everythingInProgressOf(cl, loaded("b"), SlotKind.Absent).map { it.bookId } shouldBe listOf("b", "a", "c")
            }

            test("a loaded book that is already first stays first") {
                everythingInProgressOf(cl, loaded("a"), SlotKind.Absent).map { it.bookId } shouldBe listOf("a", "b", "c")
            }

            test("a loaded book that is not in progress is not added") {
                everythingInProgressOf(cl, loaded("done"), SlotKind.JustFinished("done")).map { it.bookId } shouldBe
                    listOf("a", "b", "c")
            }

            test("with nothing loaded, the Resume book is in the list, first") {
                everythingInProgressOf(cl, LoadedKey.NothingLoaded, SlotKind.Resume("a")).map { it.bookId } shouldBe
                    listOf("a", "b", "c")
            }

            test("the only book in progress, loaded, is the whole list") {
                everythingInProgressOf(listOf(ready("a")), loaded("a"), SlotKind.Absent).map { it.bookId } shouldBe listOf("a")
            }

            test("when the Resume book is the only book in progress the list is empty: the hero is the whole story") {
                everythingInProgressOf(listOf(ready("a")), LoadedKey.NothingLoaded, SlotKind.Resume("a")) shouldBe emptyList()
            }
        }

        context("the series candidate (spec §2.3)") {
            test("walks finished books newest finish first, capped") {
                val walk = finishedNewestFirst(listOf(finished("old", 300), finished("new", 30), position("p")))
                walk shouldBe listOf("new", "old")
                finishedNewestFirst((1..30).map { finished("f$it", it.toLong()) }).size shouldBe SERIES_CANDIDATE_WALK
            }

            test("takes the first next book that is neither started nor finished") {
                val started = next("started")
                val done = next("done")
                val fresh = next("fresh")
                val positions =
                    mapOf(
                        "started" to position("started", positionMs = 500L),
                        "done" to finished("done", 900),
                        "fresh" to position("fresh", positionMs = 0L),
                    )
                seriesCandidateOf(listOf(null, started, done, fresh), positions) shouldBe fresh
            }

            test("is null when every series is caught up") {
                seriesCandidateOf(listOf(null, null), emptyMap()).shouldBeNull()
            }
        }

        context("small pure helpers") {
            test("also finished: two names, the rest counted; a nameless finisher is counted, never shown") {
                alsoFinishedOf(listOf("Maya", "", "Tom", "Ana")) shouldBe AlsoFinished(names = listOf("Maya", "Tom"), othersCount = 2)
                alsoFinishedOf(emptyList()) shouldBe AlsoFinished(names = emptyList(), othersCount = 0)
            }

            test("the hero chapter is one-based and follows the position") {
                val chapters = listOf(chapter("c1", 0L, "Prelude"), chapter("c2", 1_000L, "Bridge Four"))
                heroChapterOf(chapters, 1_500L) shouldBe HeroChapter(number = 2, title = "Bridge Four")
                heroChapterOf(emptyList(), 1_500L).shouldBeNull()
            }

            test("time-of-day bands are 5–11, 12–16, 17–20, and night") {
                listOf(4, 5, 11, 12, 16, 17, 20, 21).map(::timeOfDayAt) shouldBe
                    listOf(
                        TimeOfDay.Night,
                        TimeOfDay.Morning,
                        TimeOfDay.Morning,
                        TimeOfDay.Afternoon,
                        TimeOfDay.Afternoon,
                        TimeOfDay.Evening,
                        TimeOfDay.Evening,
                        TimeOfDay.Night,
                    )
            }

            test("connectivity: device offline first, then an unreachable server; unknown is online") {
                connectivityOf(isDeviceOnline = false, reachability = Reachability.Reachable) shouldBe HomeConnectivity.Offline
                connectivityOf(isDeviceOnline = true, reachability = Reachability.Unreachable) shouldBe
                    HomeConnectivity.ServerUnreachable
                connectivityOf(isDeviceOnline = true, reachability = Reachability.Unknown) shouldBe HomeConnectivity.Online
            }

            test("first name is the first word of the display name, or null") {
                firstNameOf("Simon Hull") shouldBe "Simon"
                firstNameOf("  ").shouldBeNull()
                firstNameOf(null).shouldBeNull()
            }
        }
    })

private fun inputs(
    loaded: LoadedKey = LoadedKey.NothingLoaded,
    cl: List<ContinueListeningItem> = emptyList(),
    justFinished: String? = null,
    hasHistory: Boolean = false,
    building: Boolean = false,
) = SlotInputs(loaded, cl, justFinished, hasHistory, building)

private fun loaded(
    bookId: String,
    isFinished: Boolean = false,
) = LoadedKey.Loaded(bookId, isFinished)

private fun position(
    bookId: String,
    positionMs: Long = 1_000L,
    lastPlayedAtMs: Long? = NOW - 10 * HOUR,
    isFinished: Boolean = false,
    finishedAtMs: Long? = null,
    dismissedAtMs: Long? = null,
) = PlaybackPosition(
    bookId = bookId,
    positionMs = positionMs,
    playbackSpeed = 1.0f,
    hasCustomSpeed = false,
    volumeBoostDb = 0f,
    hasCustomBoost = false,
    measuredGainDb = null,
    updatedAtMs = lastPlayedAtMs ?: 0L,
    syncedAtMs = null,
    lastPlayedAtMs = lastPlayedAtMs,
    isFinished = isFinished,
    finishedAtMs = finishedAtMs,
    finishedCardDismissedAtMs = dismissedAtMs,
)

private fun finished(
    bookId: String,
    hoursAgo: Long,
    dismissedAtMs: Long? = null,
) = position(
    bookId = bookId,
    positionMs = 9_000L,
    lastPlayedAtMs = NOW - hoursAgo * HOUR,
    isFinished = true,
    finishedAtMs = NOW - hoursAgo * HOUR,
    dismissedAtMs = dismissedAtMs,
)

private fun book(id: String) =
    ContinueListeningBook(
        bookId = id,
        title = "Book $id",
        authorNames = "A. Writer",
        narratorNames = null,
        coverPath = null,
        coverHash = null,
        progress = 0.5f,
        currentPositionMs = 1_000L,
        totalDurationMs = 2_000L,
        lastPlayedAtMs = NOW,
        isDownloaded = false,
    )

private fun ready(id: String) = ContinueListeningItem.Ready(id, book(id))

private fun loading(id: String) = ContinueListeningItem.Loading(id)

private fun next(id: String) =
    NextInSeries(
        bookId = id,
        title = "Next $id",
        coverPath = null,
        coverHash = null,
        narratorNames = null,
        seriesName = "Saga",
        sequenceLabel = "2",
        durationMs = 1_000L,
    )

private fun chapter(
    id: String,
    startMs: Long,
    title: String,
) = Chapter(id = id, title = title, duration = 1_000L, startTime = startMs)
