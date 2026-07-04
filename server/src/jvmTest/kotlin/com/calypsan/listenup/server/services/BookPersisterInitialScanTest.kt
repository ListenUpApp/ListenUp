@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Focused spec for [BookPersister]'s server-authoritative first-scan gate: the clean-completion path
 * stamps the library's `initial_scan_completed_at` (first-only), and the OOM/aborted path does not.
 * This is the signal the client's initial-population ("Building your library") gate reads — once
 * stamped, a rescan of the populated library never re-shows that screen.
 *
 * Split from [BookPersisterTest] (shared fixtures — `FakeBookIngest`, `persister`, `scanResult`,
 * `analyzedBook` — are reused from there) so neither spec grows past the LargeClass budget.
 */
class BookPersisterInitialScanTest :
    FunSpec({

        test("a clean scan stamps the library's initial_scan_completed_at, and a rescan does not overwrite") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)
                    val libId = LibraryRegistry(sql).currentLibrary()

                    // Never scanned yet.
                    sql.librariesQueries
                        .selectById(libId.value)
                        .executeAsOne()
                        .initial_scan_completed_at shouldBe null

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes = listOf(ChangeEventDto.Added(analyzedBook("a"))),
                            scope = ScanScope.Full,
                        ),
                    )

                    val firstStamp =
                        sql.librariesQueries
                            .selectById(libId.value)
                            .executeAsOne()
                            .initial_scan_completed_at
                    (firstStamp != null) shouldBe true

                    // A rescan of the populated library must NOT re-stamp (first-only IS NULL guard) — this
                    // is exactly what keeps the "Building your library" screen from re-appearing.
                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes = emptyList(),
                            scope = ScanScope.Full,
                        ),
                    )
                    sql.librariesQueries
                        .selectById(libId.value)
                        .executeAsOne()
                        .initial_scan_completed_at shouldBe firstStamp
                }
            }
        }

        test("the OOM/aborted path does NOT stamp initial_scan_completed_at") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest(oomForRootRelPath = setOf("b"))
                    val persister = persister(fake, scope = this)
                    val libId = LibraryRegistry(sql).currentLibrary()

                    runCatching {
                        persister.persist(
                            scanResult(
                                books = listOf(analyzedBook("a"), analyzedBook("b"), analyzedBook("c")),
                                changes =
                                    listOf(
                                        ChangeEventDto.Added(analyzedBook("a")),
                                        ChangeEventDto.Added(analyzedBook("b")),
                                        ChangeEventDto.Added(analyzedBook("c")),
                                    ),
                                scope = ScanScope.Full,
                            ),
                        )
                    }

                    // OOM aborts before the clean-path stamp — a compromised heap must not mark the library
                    // as fully scanned, so the next clean scan still shows the population screen.
                    sql.librariesQueries
                        .selectById(libId.value)
                        .executeAsOne()
                        .initial_scan_completed_at shouldBe null
                }
            }
        }
    })
