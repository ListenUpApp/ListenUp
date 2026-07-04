@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * #16 recently-added: a `FirehoseSuppressed` bulk write that CHANGED rows must be followed by exactly
 * one [SyncControl.LibraryDataChanged] broadcast. The suppressed rows are written ABOVE the client
 * cursor and never hit the lossy live tail, so this nudge is the only thing that makes connected
 * clients reconcile them live (via the client's lifecycle reconcile) — otherwise the added books
 * surface only after an app restart. This is the same rule `ImportApplier` follows.
 *
 * Split from [BookPersisterTest] (which owns the persist-orchestration concern) so each file stays
 * one concern; the shared fixtures — `persister`, `FakeBookIngest`, `scanResult`, `analyzedBook`,
 * `LARGE_INCREMENTAL_COUNT` — are the `internal` helpers declared there.
 */
class BookPersisterLibraryChangedTest :
    FunSpec({

        test("a firehose-suppressed full scan that persists books broadcasts exactly one LibraryDataChanged") {
            withSqlDatabase {
                runTest(UnconfinedTestDispatcher()) {
                    val bus = ChangeBus()
                    val persister = persister(FakeBookIngest(), scope = this, changeBus = bus)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    repeat(8) { yield() } // ensure the collector is subscribed before persist broadcasts

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes = listOf(ChangeEventDto.Added(analyzedBook("a")), ChangeEventDto.Added(analyzedBook("b"))),
                            scope = ScanScope.Full,
                        ),
                    )
                    repeat(8) { yield() } // let the post-commit broadcast drain to the collector

                    frames.map { it.control }.filter { it == SyncControl.LibraryDataChanged } shouldHaveSize 1
                }
            }
        }

        test("a firehose-suppressed large incremental that persists books broadcasts exactly one LibraryDataChanged") {
            withSqlDatabase {
                runTest(UnconfinedTestDispatcher()) {
                    val bus = ChangeBus()
                    val persister = persister(FakeBookIngest(), scope = this, changeBus = bus)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    repeat(8) { yield() }

                    // Above the suppress threshold → suppressed like a full scan, so it needs the nudge too.
                    val many = (1..LARGE_INCREMENTAL_COUNT).map { "book-$it" }
                    persister.persist(
                        scanResult(
                            books = many.map { analyzedBook(it) },
                            changes = many.map { ChangeEventDto.Added(analyzedBook(it)) },
                            scope = ScanScope.Subtree("big/folder"),
                        ),
                    )
                    repeat(8) { yield() }

                    frames.map { it.control }.filter { it == SyncControl.LibraryDataChanged } shouldHaveSize 1
                }
            }
        }

        test("a live (non-suppressed) incremental scan broadcasts no LibraryDataChanged") {
            withSqlDatabase {
                runTest(UnconfinedTestDispatcher()) {
                    val bus = ChangeBus()
                    val persister = persister(FakeBookIngest(), scope = this, changeBus = bus)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    repeat(8) { yield() }

                    // A small incremental publishes per-book live deltas — no suppressed gap, so no nudge.
                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes = listOf(ChangeEventDto.Modified(analyzedBook("a"), previousRootRelPath = "a")),
                            scope = ScanScope.Subtree("some/path"),
                        ),
                    )
                    repeat(8) { yield() }

                    frames.map { it.control } shouldNotContain SyncControl.LibraryDataChanged
                }
            }
        }

        test("a firehose-suppressed scan that persists zero books broadcasts no LibraryDataChanged") {
            withSqlDatabase {
                runTest(UnconfinedTestDispatcher()) {
                    val bus = ChangeBus()
                    val persister = persister(FakeBookIngest(), scope = this, changeBus = bus)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    repeat(8) { yield() }

                    // A full scan is suppressed, but nothing changed (books present, changes empty) → no
                    // rows written above the cursor → nothing to reconcile → no wasteful nudge.
                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes = emptyList(),
                            scope = ScanScope.Full,
                        ),
                    )
                    repeat(8) { yield() }

                    frames.map { it.control } shouldNotContain SyncControl.LibraryDataChanged
                }
            }
        }
    })
