package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.FakePeopleSource
import com.calypsan.listenup.server.matching.PeopleFinder
import com.calypsan.listenup.server.matching.PeopleSubjectLoader
import com.calypsan.listenup.server.matching.person
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.PersonStep
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.ExternalRefKind
import com.calypsan.listenup.server.services.replaceExternalRefs
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
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

private class PeopleServiceRig(
    db: SqlTestDatabases,
) {
    val contributors = ContributorRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry())
    val hardcover = FakePeopleSource(MetadataProviderId.HARDCOVER, setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR))
    private val loader = PeopleSubjectLoader(db.sql, contributors, BookAccessPolicy(db.sql, db.driver))
    val service =
        MatchingServiceImpl(
            finder = BookFinder(MetadataProviderRegistry(emptyList()), EnrichmentRoutes.DEFAULT),
            loadBook = { null },
            libraryRegion = { null },
            permissionPolicy = UserPermissionPolicy(db.sql),
            bookAccessPolicy = BookAccessPolicy(db.sql, db.driver),
            peopleFinder = PeopleFinder(MetadataProviderRegistry(listOf(hardcover)), EnrichmentRoutes.DEFAULT),
            loadPeople = loader::load,
            peopleRegion = loader::region,
            details = com.calypsan.listenup.server.matching.apply.MatchRig(db).details(),
        )
}

/** Ray Porter, narrating two books (one with an ASIN, one linked to Hardcover) and authoring a third. */
private suspend fun SqlTestDatabases.seedPorter(rig: PeopleServiceRig): ContributorId {
    sql.seedTestLibraryAndFolder()
    sql.seedTestBook("phm", asin = "B08G9RZBTT")
    sql.seedTestBook("hr")
    sql.seedTestBook("memoir")
    val porter = rig.contributors.resolveOrCreate("Ray Porter", sortName = null)
    sql.transaction {
        sql.bookContributorsQueries.insert("phm", porter.value, "narrator", null, 0)
        sql.bookContributorsQueries.insert("hr", porter.value, "narrator", null, 0)
        sql.bookContributorsQueries.insert("memoir", porter.value, "author", null, 0)
        sql.replaceExternalRefs(ExternalRefKind.BOOK, "hr", listOf(ExternalRef("hardcover", "428002")))
    }
    return porter
}

/** `findPeople`'s gate, its input checks, and the subject it searches from: your books in that role, as you see them. */
class MatchingServiceImplPeopleTest :
    FunSpec({
        test("a narrator Find reads the books he narrates, with their identifiers, and ranks by them") {
            withSqlDatabase {
                val rig = PeopleServiceRig(this)
                runTest {
                    val porter = seedPorter(rig)
                    rig.hardcover.answers(
                        listOf(person("250716", credited = setOf("phm", "hr"))),
                        setOf(PersonStep.NAME, PersonStep.VIA_BOOKS),
                    )

                    val result =
                        rig.service
                            .copyWith(rootPrincipal())
                            .findPeople(porter, PersonFindRequest(ContributorRole.NARRATOR))
                            .shouldBeInstanceOf<AppResult.Success<PersonFindResult>>()
                            .data

                    val asked = rig.hardcover.asked.single()
                    asked.name shouldBe "Ray Porter"
                    asked.books.map { it.bookId to (it.asin ?: it.refs.single().id) }.toSet() shouldBe
                        setOf("phm" to "B08G9RZBTT", "hr" to "428002")
                    result.inLibrary.bookCount shouldBe 2
                    result.candidates.single().tier shouldBe MatchTier.STRONG
                }
            }
        }

        test("a member sees only the books they can reach") {
            withSqlDatabase {
                val rig = PeopleServiceRig(this)
                runTest {
                    val porter = seedPorter(rig)
                    sql.seedTestUser("m", UserRoleColumn.MEMBER, canEdit = true)

                    val result =
                        rig.service
                            .copyWith(memberPrincipal("m"))
                            .findPeople(porter, PersonFindRequest(ContributorRole.NARRATOR))
                            .shouldBeInstanceOf<AppResult.Success<PersonFindResult>>()
                            .data

                    result.inLibrary.bookCount shouldBe 0
                    rig.hardcover.asked
                        .single()
                        .books shouldBe emptyList()
                }
            }
        }

        test("someone who can't edit is refused before any catalogue is asked") {
            withSqlDatabase {
                val rig = PeopleServiceRig(this)
                runTest {
                    val porter = seedPorter(rig)
                    sql.seedTestUser("viewer", UserRoleColumn.MEMBER, canEdit = false)
                    rig.service
                        .copyWith(memberPrincipal("viewer"))
                        .findPeople(porter, PersonFindRequest(ContributorRole.NARRATOR))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    rig.hardcover.asked shouldBe emptyList()
                }
            }
        }

        test("an unknown contributor is not found; another role or an overlong query is malformed") {
            withSqlDatabase {
                val rig = PeopleServiceRig(this)
                val admin = rig.service.copyWith(rootPrincipal())
                runTest {
                    admin
                        .findPeople(ContributorId("nobody"), PersonFindRequest(ContributorRole.AUTHOR))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.NotFound>()
                    admin
                        .findPeople(ContributorId("nobody"), PersonFindRequest(ContributorRole.TRANSLATOR))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                    admin
                        .findPeople(ContributorId("nobody"), PersonFindRequest(ContributorRole.AUTHOR, "x".repeat(201)))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                }
            }
        }
    })
