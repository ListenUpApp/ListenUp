package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ContributorRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val AUDIBLE = MetadataSource(id = "audible", label = "Audible")
private val HARDCOVER = MetadataSource(id = "hardcover", label = "Hardcover")

private val RESULT =
    PersonFindResult(
        role = ContributorRole.NARRATOR,
        steps =
            listOf(
                PersonSearchStep.ExistingLink(HARDCOVER),
                PersonSearchStep.ViaYourBooks(5),
                PersonSearchStep.ByName("Ray Porter"),
            ),
        inLibrary = InLibrary(bookCount = 7, titles = listOf("Project Hail Mary", "Heaven's River", "Artemis")),
        coverage = listOf(RoleCoverage(AUDIBLE, hasProfiles = false), RoleCoverage(HARDCOVER, hasProfiles = true)),
        candidates =
            listOf(
                PersonCandidate(
                    key = PersonCandidateKey(listOf(ExternalRef("hardcover", "250716"))),
                    name = "Ray Porter",
                    roles = listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                    photoUrl = "https://assets.hardcover.app/author/250716/p.jpg",
                    knownWorks = listOf("Project Hail Mary", "Heaven's River"),
                    worksCount = 64,
                    libraryCount = 5,
                    foundIn = listOf(HARDCOVER),
                    tier = MatchTier.STRONG,
                    isBest = true,
                    isCurrentLink = false,
                    reasons = listOf(PersonReason.DifferentRole(listOf(ContributorRole.AUTHOR)), PersonReason.NoBooksInLibrary),
                ),
            ),
        sources =
            listOf(
                SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NO_PROFILES_FOR_ROLE),
                SourceStatus.Answered(HARDCOVER, 1),
            ),
    )

/** Every people Find type crosses the wire intact. */
class PeopleFindContractTest :
    FunSpec({
        test("a people Find result round-trips") {
            val json = contractJson.encodeToString(PersonFindResult.serializer(), RESULT)
            contractJson.decodeFromString(PersonFindResult.serializer(), json) shouldBe RESULT
        }

        test("a request round-trips, and its query is optional") {
            val request = PersonFindRequest(ContributorRole.AUTHOR, query = "R. Porter")
            contractJson.decodeFromString(
                PersonFindRequest.serializer(),
                contractJson.encodeToString(PersonFindRequest.serializer(), request),
            ) shouldBe request
            contractJson.decodeFromString(PersonFindRequest.serializer(), """{"role":"NARRATOR"}""") shouldBe
                PersonFindRequest(ContributorRole.NARRATOR)
        }

        test("a candidate key needs a ref") {
            shouldThrow<IllegalArgumentException> { PersonCandidateKey(emptyList()) }
        }
    })
