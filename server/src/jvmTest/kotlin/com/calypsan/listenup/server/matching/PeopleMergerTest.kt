package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val AUDIBLE = MetadataProviderId.AUDIBLE
private val HARDCOVER = MetadataProviderId.HARDCOVER

/** People merge only on strong evidence: the same name *and* a library book both sources credit them on. */
class PeopleMergerTest :
    FunSpec({
        test("the same name credited on the same book at two sources is one person, with both refs") {
            val merged =
                PeopleMerger
                    .merge(
                        listOf(
                            SourcedPerson(AUDIBLE, person("B001", "Andy Weir", setOf(ContributorRole.AUTHOR), setOf("phm"))),
                            SourcedPerson(
                                HARDCOVER,
                                person(
                                    "123645",
                                    "Weir, Andy",
                                    setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                                    setOf("phm", "artemis"),
                                    photoUrl = "p",
                                ),
                            ),
                        ),
                    ).single()

            merged.refs shouldBe listOf(ExternalRef("audible", "B001"), ExternalRef("hardcover", "123645"))
            merged.name shouldBe "Andy Weir"
            merged.roles shouldBe setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)
            merged.photoUrl shouldBe "p"
            merged.creditedBookIds shouldBe setOf("phm", "artemis")
        }

        test("the same name with no book in common stays two people") {
            PeopleMerger
                .merge(
                    listOf(
                        SourcedPerson(AUDIBLE, person("B001", "Andy Weir", credited = setOf("phm"))),
                        SourcedPerson(HARDCOVER, person("188554", "Andy Weir", credited = emptySet())),
                    ),
                ).size shouldBe 2
        }

        test("two same-named people at one source never merge") {
            PeopleMerger
                .merge(
                    listOf(
                        SourcedPerson(HARDCOVER, person("1", credited = setOf("phm"))),
                        SourcedPerson(HARDCOVER, person("2", credited = setOf("phm"))),
                    ),
                ).size shouldBe 2
        }

        test("a different name on the same book stays apart") {
            PeopleMerger
                .merge(
                    listOf(
                        SourcedPerson(AUDIBLE, person("B001", "Ray Porter", credited = setOf("phm"))),
                        SourcedPerson(HARDCOVER, person("9", "Ray Porterfield", credited = setOf("phm"))),
                    ),
                ).size shouldBe 2
        }
    })
