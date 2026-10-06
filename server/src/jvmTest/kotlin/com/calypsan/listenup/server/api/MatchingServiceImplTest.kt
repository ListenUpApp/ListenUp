package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.FakeRegionalFindSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private class ServiceRig(
    db: SqlTestDatabases,
    libraryRegion: String? = null,
    book: BookSyncPayload? = bookPayloadFixture(id = "b1", title = "Project Hail Mary"),
) {
    val audible = FakeRegionalFindSource(MetadataProviderId.AUDIBLE)
    val service =
        MatchingServiceImpl(
            finder = BookFinder(MetadataProviderRegistry(listOf(audible)), EnrichmentRoutes.DEFAULT),
            loadBook = { id -> book?.takeIf { it.id == id.value } },
            libraryRegion = { libraryRegion },
            permissionPolicy = UserPermissionPolicy(db.sql),
            bookAccessPolicy = BookAccessPolicy(db.sql, db.driver),
        )
}

/** Find's gate (spec: `requireCanEdit` on the book), its input checks, and the store it starts in. */
class MatchingServiceImplTest :
    FunSpec({
        test("a member who can't edit is refused before any catalogue is asked") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("m", UserRoleColumn.MEMBER, canEdit = false)
                val rig = ServiceRig(this)
                runTest {
                    val refused = rig.service.copyWith(memberPrincipal("m")).findBookMatches(BookId("b1"), BookFindRequest())
                    refused.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                    rig.audible.asked shouldBe emptyList()
                }
            }
        }

        test("a book the caller can't see is not found, worded exactly like a missing one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("m")
                sql.seedTestBook("hidden")
                val rig = ServiceRig(this)
                runTest {
                    val denial = rig.service.copyWith(memberPrincipal("m")).findBookMatches(BookId("hidden"), BookFindRequest())
                    denial.shouldBeInstanceOf<AppResult.Failure>().error shouldBe
                        MetadataError.NotFound(debugInfo = "no book for id hidden")
                }
            }
        }

        test("an overlong query or an unknown store is refused as malformed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val admin = ServiceRig(this).service.copyWith(rootPrincipal())
                runTest {
                    admin
                        .findBookMatches(BookId("b1"), BookFindRequest(query = "x".repeat(201)))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                    admin
                        .findBookMatches(BookId("b1"), BookFindRequest(regionOverride = MetadataLocale("zz")))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                }
            }
        }

        test("Find starts in the library's store, and this search's pick beats it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val admin = ServiceRig(this, libraryRegion = "uk").service.copyWith(rootPrincipal())
                runTest {
                    val library =
                        admin.findBookMatches(BookId("b1"), BookFindRequest()).shouldBeInstanceOf<AppResult.Success<BookFindResult>>()
                    library.data.region?.region shouldBe MetadataLocale("uk")
                    library.data.region?.origin shouldBe RegionOrigin.LIBRARY

                    val picked =
                        admin
                            .findBookMatches(BookId("b1"), BookFindRequest(regionOverride = MetadataLocale("au")))
                            .shouldBeInstanceOf<AppResult.Success<BookFindResult>>()
                    picked.data.region?.region shouldBe MetadataLocale("au")
                    picked.data.region?.origin shouldBe RegionOrigin.SEARCH_OVERRIDE
                }
            }
        }

        test("a book the server can't load is not found") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b2")
                val admin = ServiceRig(this).service.copyWith(rootPrincipal())
                runTest {
                    admin.findBookMatches(BookId("b2"), BookFindRequest()).shouldBeInstanceOf<AppResult.Failure>().error shouldBe
                        MetadataError.NotFound(debugInfo = "no book for id b2")
                }
            }
        }
    })
