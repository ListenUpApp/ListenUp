package com.calypsan.listenup.server.metadata.spi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContributorHitRankerTest :
    FunSpec({

        test("exact name match ranks above decorated and extended variants") {
            val hits =
                listOf(
                    ContributorHitMeta(key = "B1", name = "Timothy Curry Jr."),
                    ContributorHitMeta(key = "B2", name = "Tim Curry"),
                    ContributorHitMeta(key = "B3", name = "Tim Curry - introductions"),
                )

            val ranked = ContributorHitRanker.rank("Tim Curry", hits)

            ranked.first().key shouldBe "B2"
        }

        test("token order does not matter — sort-name form matches the natural form") {
            val hits =
                listOf(
                    ContributorHitMeta(key = "B1", name = "Stephen Fry"),
                    ContributorHitMeta(key = "B2", name = "King, Stephen"),
                )

            ContributorHitRanker.rank("Stephen King", hits).first().key shouldBe "B2"
        }

        test("equal scores keep the catalog's original order (stable sort)") {
            val hits =
                listOf(
                    ContributorHitMeta(key = "B1", name = "Tim Curry"),
                    ContributorHitMeta(key = "B2", name = "Tim Curry"),
                )

            ContributorHitRanker.rank("Tim Curry", hits).map { it.key } shouldBe listOf("B1", "B2")
        }

        test("blank query preserves the input order") {
            val hits =
                listOf(
                    ContributorHitMeta(key = "B1", name = "Alpha"),
                    ContributorHitMeta(key = "B2", name = "Beta"),
                )

            ContributorHitRanker.rank("   ", hits) shouldBe hits
        }
    })
