package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldDecision
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetChange
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.FakeRegionalFindSource
import com.calypsan.listenup.server.matching.PeopleFinder
import com.calypsan.listenup.server.matching.apply.BOOK
import com.calypsan.listenup.server.matching.apply.MatchRig
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun MatchRig.service(): MatchingServiceImpl {
    val audibleFind = FakeRegionalFindSource(MetadataProviderId.AUDIBLE)
    audibleFind.answers(
        listOf(
            FoundBook(
                key = "B0X",
                title = "Project Hail Mary",
                authors = listOf("Andy Weir"),
                narrators = listOf("Ray Porter"),
                durationMs = 3_000L,
                region = "us",
            ),
        ),
    )
    return MatchingServiceImpl(
        finder = BookFinder(MetadataProviderRegistry(listOf(audibleFind)), EnrichmentRoutes.DEFAULT),
        loadBook = { books.findById(it) },
        libraryRegion = { null },
        permissionPolicy = PermissionPolicy(db.sql),
        bookAccessPolicy = BookAccessPolicy(db.sql, db.driver),
        peopleFinder = PeopleFinder(MetadataProviderRegistry(emptyList()), EnrichmentRoutes.DEFAULT),
        loadPeople = { _, _ -> null },
        peopleRegion = { com.calypsan.listenup.api.metadata.MetadataLocale.DEFAULT },
        details = details(),
    )
}

private val AUDIBLE_KEY = BookCandidateKey(listOf(ExternalRef("audible", "B0X", "us")))

private fun AppResult<*>.error() = (this as AppResult.Failure).error

/** Match details end to end over the real server pieces: Find → Review → Apply → Undo, and the edit gate on each. */
class MatchDetailsServiceTest :
    FunSpec({
        test("Find → Review → Apply → Undo, each through the service, read-your-writes on the way") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    val before = rig.seedBook()
                    val admin = rig.service().copyWith(rootPrincipal())

                    val found = admin.findBookMatches(BookId(BOOK), BookFindRequest()).shouldSucceed()
                    val key = found.candidates.first().key
                    key.refs.single().id shouldBe "B0X"

                    val review = admin.reviewBookMatch(BookId(BOOK), key, null).shouldSucceed()
                    val request =
                        BookMatchApply(
                            candidate = key,
                            region = null,
                            basedOnRevision = review.basedOnRevision,
                            fields = review.fields.map { FieldDecision(it.field, it.defaultChoice) },
                            cover = review.cover.defaultChoice,
                            genres = LabelSetChange(add = review.genres.suggested.map { it.label }),
                            moods = LabelSetChange(add = review.moods.suggested.map { it.label }),
                            chapterOrdinals = emptyList(),
                        )
                    val applied = admin.applyBookMatch(BookId(BOOK), request).shouldSucceed()
                    applied.frames.count { it.domain == SyncDomains.BOOKS.name } shouldBe 1
                    rig.book().description shouldBe "New description."
                    rig.book().lastMatch?.receiptId shouldBe applied.value.receiptId

                    val undone = admin.undoMatch(applied.value.receiptId).shouldSucceed()
                    undone.frames.count { it.domain == SyncDomains.BOOKS.name } shouldBe 1
                    rig.book().description shouldBe before.description
                    rig.book().lastMatch.shouldBeNull()
                    admin.undoMatch(applied.value.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                }
            }
        }

        test("Review, Apply and Undo refuse a member who can't edit") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val admin = rig.service().copyWith(rootPrincipal())
                    val review = admin.reviewBookMatch(BookId(BOOK), AUDIBLE_KEY, null).shouldSucceed()
                    val applied =
                        admin
                            .applyBookMatch(
                                BookId(BOOK),
                                BookMatchApply(
                                    AUDIBLE_KEY,
                                    null,
                                    review.basedOnRevision,
                                    emptyList(),
                                    ImageChoice.KeepCurrent,
                                    LabelSetChange(),
                                    LabelSetChange(),
                                    emptyList(),
                                ),
                            ).shouldSucceed()

                    rig.db.sql.seedTestUser("m", UserRoleColumn.MEMBER, canEdit = false)
                    val member = rig.service().copyWith(memberPrincipal("m"))
                    member.reviewBookMatch(BookId(BOOK), AUDIBLE_KEY, null).error().shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member
                        .applyBookMatch(
                            BookId(BOOK),
                            BookMatchApply(
                                AUDIBLE_KEY,
                                null,
                                1,
                                emptyList(),
                                ImageChoice.KeepCurrent,
                                LabelSetChange(),
                                LabelSetChange(),
                                emptyList(),
                            ),
                        ).error()
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member.undoMatch(applied.value.receiptId).error().shouldBeInstanceOf<AuthError.PermissionDenied>()
                    rig.book().lastMatch?.receiptId shouldBe applied.value.receiptId
                }
            }
        }

        test("a book the caller can't see is not found, for Review as for Find") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig.db.sql.seedTestUser("m")
                    rig.db.sql.seedTestBook("hidden")
                    val member = rig.service().copyWith(memberPrincipal("m"))
                    member.reviewBookMatch(BookId("hidden"), AUDIBLE_KEY, null).error().shouldBeInstanceOf<MetadataError.NotFound>()
                }
            }
        }

        test("an unknown store is refused as malformed") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig
                        .service()
                        .copyWith(rootPrincipal())
                        .reviewBookMatch(
                            BookId(BOOK),
                            AUDIBLE_KEY,
                            com.calypsan.listenup.api.metadata
                                .MetadataLocale("zz"),
                        ).error()
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                }
            }
        }
    })
