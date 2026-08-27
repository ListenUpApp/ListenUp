package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private val LIBRARY = LibraryId("test-library")
private const val ROOT = "/srv/audiobooks"

/**
 * [ScanIssueRepository] and [reconcile] — the durable record of what the scanner could not import.
 *
 * The load-bearing test here is the **round trip**: a folder that fails, is fixed, and imports must
 * end with no open issue. That is the entire promise of the feature, and it is the one thing a
 * repository test can prove that a UI cannot. It is also where the two sides can silently disagree
 * — the scanner reports failures against *absolute* paths and imports against *library-relative*
 * ones, so a reconcile that does not reconcile those path spaces clears nothing while appearing to
 * work perfectly.
 */
class ScanIssueRepositoryTest :
    FunSpec({

        fun failure(
            relPath: String,
            detail: String = "boom",
        ): ScanError =
            // Exactly the shape Scanner.toScanError produces: folder root joined to the book's
            // relative path, i.e. ABSOLUTE. Constructing it any other way would test a fiction.
            ScanError.FileUnreadable(path = "$ROOT/$relPath", debugInfo = detail)

        test("records an issue and reads it back") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)

                    repo.record(LIBRARY, "Author/Broken Book", ScanIssueReason.NO_RECOGNIZED_AUDIO, "no audio")

                    val open = repo.listOpen(LIBRARY)
                    open.single().rootRelPath shouldBe "Author/Broken Book"
                    open.single().reason shouldBe ScanIssueReason.NO_RECOGNIZED_AUDIO
                    open.single().detail shouldBe "no audio"
                }
            }
        }

        test("a repeated failure keeps first_seen but moves last_seen") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)
                    repo.record(LIBRARY, "Author/Broken", ScanIssueReason.FILE_UNREADABLE, "first")
                    val first = repo.listOpen(LIBRARY).single()

                    repo.record(LIBRARY, "Author/Broken", ScanIssueReason.FILE_UNREADABLE, "second")

                    val again = repo.listOpen(LIBRARY)
                    withClue("the same broken folder is one problem, not two") { again.size shouldBe 1 }
                    withClue("'broken for a month' must stay distinguishable from 'broke today'") {
                        again.single().firstSeenAt shouldBe first.firstSeenAt
                    }
                    again.single().detail shouldBe "second"
                }
            }
        }

        test("clearing a fixed folder removes its issue") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)
                    repo.record(LIBRARY, "Author/Broken", ScanIssueReason.FILE_UNREADABLE, null)

                    repo.clear(LIBRARY, "Author/Broken")

                    repo.listOpen(LIBRARY).shouldBeEmpty()
                }
            }
        }

        test("THE ROUND TRIP — a folder that fails, is fixed, then imports has no open issue") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)

                    // Scan one: the folder fails. The scanner reports an absolute path.
                    repo.reconcile(
                        libraryId = LIBRARY,
                        folderRoots = listOf(ROOT),
                        importedRelPaths = emptyList(),
                        errors = listOf(failure("Author/Fixable Book")),
                    )
                    withClue("the failure must be recorded, and under a path the user recognises") {
                        repo.listOpen(LIBRARY).single().rootRelPath shouldBe "Author/Fixable Book"
                    }

                    // The user fixes it on disk. Scan two: the folder imports. The scanner reports
                    // the imported book by its LIBRARY-RELATIVE path.
                    repo.reconcile(
                        libraryId = LIBRARY,
                        folderRoots = listOf(ROOT),
                        importedRelPaths = listOf("Author/Fixable Book"),
                        errors = emptyList(),
                    )

                    withClue("a notice list that keeps showing fixed problems trains you to ignore it") {
                        repo.listOpen(LIBRARY).shouldBeEmpty()
                    }
                }
            }
        }

        test("reconcile records a failure and clears a success in the same pass") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)
                    repo.record(LIBRARY, "Author/Now Fine", ScanIssueReason.FILE_UNREADABLE, null)

                    repo.reconcile(
                        libraryId = LIBRARY,
                        folderRoots = listOf(ROOT),
                        importedRelPaths = listOf("Author/Now Fine"),
                        errors = listOf(failure("Author/Now Broken")),
                    )

                    repo.listOpen(LIBRARY).map { it.rootRelPath } shouldBe listOf("Author/Now Broken")
                }
            }
        }

        test("dismissing hides an issue") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)
                    repo.record(LIBRARY, "Author/Whatever", ScanIssueReason.NO_RECOGNIZED_AUDIO, null)
                    val id = repo.listOpen(LIBRARY).single().id

                    repo.dismiss(id)

                    repo.listOpen(LIBRARY).shouldBeEmpty()
                }
            }
        }

        test("a dismissed folder failing the SAME way again stays dismissed") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)
                    // The `_artwork` folder that will never be a book. The user has said so.
                    repo.record(LIBRARY, "Author/_artwork", ScanIssueReason.NO_RECOGNIZED_AUDIO, null)
                    repo.dismiss(repo.listOpen(LIBRARY).single().id)

                    repo.record(LIBRARY, "Author/_artwork", ScanIssueReason.NO_RECOGNIZED_AUDIO, null)

                    withClue("'stop telling me about this folder' must not expire on the next scan") {
                        repo.listOpen(LIBRARY).shouldBeEmpty()
                    }
                }
            }
        }

        test("a dismissed folder failing a DIFFERENT way is raised again") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)
                    repo.record(LIBRARY, "Author/Odd", ScanIssueReason.NO_RECOGNIZED_AUDIO, null)
                    repo.dismiss(repo.listOpen(LIBRARY).single().id)

                    repo.record(LIBRARY, "Author/Odd", ScanIssueReason.FILE_UNREADABLE, "permission denied")

                    withClue("a different failure is new information, not the thing they dismissed") {
                        repo.listOpen(LIBRARY).single().reason shouldBe ScanIssueReason.FILE_UNREADABLE
                    }
                }
            }
        }

        test("an unplaceable path is still recorded rather than silently dropped") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)

                    repo.reconcile(
                        libraryId = LIBRARY,
                        folderRoots = listOf(ROOT),
                        importedRelPaths = emptyList(),
                        errors = listOf(ScanError.FileUnreadable(path = "/somewhere/else/Book")),
                    )

                    withClue("an ugly path beats a lost failure") {
                        repo.listOpen(LIBRARY).single().rootRelPath shouldBe "/somewhere/else/Book"
                    }
                }
            }
        }

        test("a whole-scan fault names no folder, so it raises no issue") {
            withSqlDatabase {
                runTest {
                    val repo = ScanIssueRepository(sql)

                    repo.reconcile(
                        libraryId = LIBRARY,
                        folderRoots = listOf(ROOT),
                        importedRelPaths = emptyList(),
                        errors = listOf(ScanError.LibraryPathNotFound(path = ROOT)),
                    )

                    withClue("an unreachable library is a server problem, not one broken book") {
                        repo.listOpen(LIBRARY).shouldBeEmpty()
                    }
                }
            }
        }
    })
