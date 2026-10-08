package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ContributorRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val AUDIBLE = MetadataSource(id = "audible", label = "Audible")
private val HARDCOVER = MetadataSource(id = "hardcover", label = "Hardcover")

private val RESULT =
    PersonFindResult(
        steps =
            listOf(
                PersonSearchStep.ExistingLink(HARDCOVER),
                PersonSearchStep.ViaYourBooks(5),
                PersonSearchStep.ByName("Ray Porter"),
            ),
        inLibrary = InLibrary(bookCount = 7, titles = listOf("Project Hail Mary", "Heaven's River", "Artemis")),
        coverage = listOf(RoleCoverage(AUDIBLE, hasProfiles = true), RoleCoverage(HARDCOVER, hasProfiles = true)),
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
                    reasons = listOf(PersonReason.NoBooksInLibrary),
                    libraryCredits =
                        listOf(LibraryCredit(ContributorRole.NARRATOR, 3), LibraryCredit(ContributorRole.TRANSLATOR, 1)),
                ),
            ),
        sources =
            listOf(
                SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NOT_CONFIGURED),
                SourceStatus.Answered(HARDCOVER, 1),
            ),
    )

/** The fields a client shipped before people matching went role-free requires on every candidate. */
private val LEGACY_CANDIDATE_FIELDS =
    setOf(
        "key", "name", "roles", "photoUrl", "knownWorks", "worksCount", "libraryCount", "foundIn", "tier", "isBest",
        "isCurrentLink", "reasons",
    )

/** Every people Find type crosses the wire intact, and older clients — which name a role — still decode. */
class PeopleFindContractTest :
    FunSpec({
        test("a people Find result round-trips") {
            val json = contractJson.encodeToString(PersonFindResult.serializer(), RESULT)
            contractJson.decodeFromString(PersonFindResult.serializer(), json) shouldBe RESULT
        }

        test("a request needs no role, and its query is optional") {
            val request = PersonFindRequest(query = "R. Porter")
            contractJson.encodeToString(PersonFindRequest.serializer(), request) shouldBe """{"query":"R. Porter"}"""
            contractJson.decodeFromString(PersonFindRequest.serializer(), "{}") shouldBe PersonFindRequest()
        }

        test("an older client's request, which names a role, still decodes") {
            contractJson.decodeFromString(PersonFindRequest.serializer(), """{"role":"NARRATOR"}""") shouldBe
                PersonFindRequest(role = ContributorRole.NARRATOR)
        }

        test("a result for an older client carries every field it requires, its role echoed") {
            val json =
                contractJson
                    .encodeToJsonElement(PersonFindResult.serializer(), RESULT.copy(role = ContributorRole.NARRATOR))
                    .jsonObject
            json.keys shouldBe setOf("role", "steps", "inLibrary", "coverage", "candidates", "sources")
            json.getValue("role").jsonPrimitive.content shouldBe "NARRATOR"
            (json.getValue("candidates") as kotlinx.serialization.json.JsonArray)
                .map { (it as JsonObject).keys }
                .single()
                .containsAll(LEGACY_CANDIDATE_FIELDS) shouldBe true
        }

        test("a result for a new client names no role") {
            contractJson.encodeToJsonElement(PersonFindResult.serializer(), RESULT).jsonObject.keys.contains("role") shouldBe
                false
        }

        test("a candidate key needs a ref") {
            shouldThrow<IllegalArgumentException> { PersonCandidateKey(emptyList()) }
        }
    })
