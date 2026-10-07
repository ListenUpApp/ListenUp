package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.PeopleFinder
import com.calypsan.listenup.server.matching.apply.MatchRig
import com.calypsan.listenup.server.matching.person.PERSON_KEY
import com.calypsan.listenup.server.matching.person.PERSON_US
import com.calypsan.listenup.server.matching.person.PersonRig
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.calypsan.listenup.api.dto.match.ImageChoice
import kotlinx.coroutines.test.runTest

private fun SqlTestDatabases.personService(people: PersonRig): MatchingServiceImpl {
    val books = MatchRig(this)
    return MatchingServiceImpl(
        finder = BookFinder(MetadataProviderRegistry(emptyList()), EnrichmentRoutes.DEFAULT),
        loadBook = { null },
        libraryRegion = { null },
        permissionPolicy = UserPermissionPolicy(sql),
        bookAccessPolicy = BookAccessPolicy(sql, driver),
        peopleFinder = PeopleFinder(MetadataProviderRegistry(emptyList()), EnrichmentRoutes.DEFAULT),
        loadPeople = { _, _, _ -> null },
        peopleRegion = { PERSON_US },
        details = MatchDetails(books.reviewer, books.applier, books.undoer, books.receipts, people.people()),
    )
}

private fun <T> AppResult<T>.value(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private fun AppResult<*>.error() = shouldBeInstanceOf<AppResult.Failure>().error

/** The person half of Match details through the service: Review → Apply → Undo, its gate and its input checks. */
class MatchingServiceImplPersonMatchTest :
    FunSpec({
        test("Review, then Apply, then Undo — each hands the person's change back for read-your-writes") {
            withSqlDatabase {
                runTest {
                    val people = PersonRig(this@withSqlDatabase)
                    val before = people.seedRay()
                    val service = personService(people).copyWith(rootPrincipal())
                    val ray = ContributorId(people.rayId)

                    val review: PersonMatchReview = service.reviewPersonMatch(ray, PERSON_KEY, ContributorRole.NARRATOR).value()
                    val applied: Mutated<MatchReceipt> =
                        service
                            .applyPersonMatch(
                                ray,
                                PersonMatchApply(
                                    candidate = PERSON_KEY,
                                    role = ContributorRole.NARRATOR,
                                    basedOnRevision = review.basedOnRevision,
                                    photo = review.photo.defaultChoice,
                                    biography = review.biography!!.defaultChoice,
                                ),
                            ).value()
                    applied.value.changes.map { it::class } shouldBe
                        listOf(AppliedChange.Photo::class, AppliedChange.Biography::class)
                    applied.frames.single().domain shouldBe SyncDomains.CONTRIBUTORS.name
                    people.person().description shouldBe "Audible bio."

                    val undone: Mutated<UndoResult> = service.undoMatch(applied.value.receiptId).value()
                    undone.frames.single().domain shouldBe SyncDomains.CONTRIBUTORS.name
                    people.person().description shouldBe before.description
                    people.person().imagePath shouldBe before.imagePath
                }
            }
        }

        test("someone who can't edit can't review, apply or undo a person match") {
            withSqlDatabase {
                runTest {
                    val people = PersonRig(this@withSqlDatabase)
                    people.seedRay()
                    sql.seedTestUser("viewer", UserRoleColumn.MEMBER, canEdit = false)
                    val ray = ContributorId(people.rayId)
                    val receipt = people.apply(people.defaultRequest()).value()
                    val viewer = personService(people).copyWith(memberPrincipal("viewer"))

                    viewer
                        .reviewPersonMatch(ray, PERSON_KEY, ContributorRole.NARRATOR)
                        .error()
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    viewer
                        .applyPersonMatch(ray, people.defaultRequest())
                        .error()
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    viewer.undoMatch(receipt.receiptId).error().shouldBeInstanceOf<AuthError.PermissionDenied>()
                    people.receipts.find(receipt.receiptId)!!.undoneAt shouldBe null
                }
            }
        }

        test("an unknown or merged-away person is not found") {
            withSqlDatabase {
                runTest {
                    val people = PersonRig(this@withSqlDatabase)
                    people.seedRay()
                    val service = personService(people).copyWith(rootPrincipal())
                    service
                        .reviewPersonMatch(ContributorId("nobody"), PERSON_KEY, ContributorRole.AUTHOR)
                        .error()
                        .shouldBeInstanceOf<MetadataError.NotFound>()
                    people.contributors.softDelete(ContributorId(people.rayId))
                    service
                        .reviewPersonMatch(ContributorId(people.rayId), PERSON_KEY, ContributorRole.AUTHOR)
                        .error()
                        .shouldBeInstanceOf<MetadataError.NotFound>()
                }
            }
        }

        test("people are matched as authors or narrators only") {
            withSqlDatabase {
                runTest {
                    val people = PersonRig(this@withSqlDatabase)
                    people.seedRay()
                    val service = personService(people).copyWith(rootPrincipal())
                    val ray = ContributorId(people.rayId)
                    service
                        .reviewPersonMatch(ray, PERSON_KEY, ContributorRole.TRANSLATOR)
                        .error()
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                    service
                        .applyPersonMatch(ray, people.defaultRequest().copy(role = ContributorRole.EDITOR))
                        .error()
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                }
            }
        }

        test("keeping both photo and biography still links the person, with an empty receipt") {
            withSqlDatabase {
                runTest {
                    val people = PersonRig(this@withSqlDatabase)
                    people.seedRay()
                    val service = personService(people).copyWith(rootPrincipal())
                    val request =
                        people.defaultRequest().copy(
                            photo = ImageChoice.KeepCurrent,
                            biography = FieldChoice.KeepCurrent,
                        )
                    val applied = service.applyPersonMatch(ContributorId(people.rayId), request).value()
                    applied.value.changes shouldBe emptyList()
                    people
                        .person()
                        .externalRefs
                        .map { it.id }
                        .toSet() shouldBe setOf("B0RAY", "ray", "250716")
                }
            }
        }
    })
