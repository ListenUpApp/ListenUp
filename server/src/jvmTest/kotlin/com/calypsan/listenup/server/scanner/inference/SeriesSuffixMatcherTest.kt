package com.calypsan.listenup.server.scanner.inference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeriesSuffixMatcherTest :
    FunSpec({
        context("stripTrailingSeriesSuffix removes a strict trailing Book/Vol+number suffix") {
            listOf(
                "Harry Potter and the Half-Blood Prince, Book 6" to "Harry Potter and the Half-Blood Prince",
                "Harry Potter and the Sorcerer's Stone, Book 1" to "Harry Potter and the Sorcerer's Stone",
                // Multi-colon: an earlier colon already carries the subtitle, so the trailing colon
                // segment is confidently a series tail and is dropped whole.
                "The Land: Alliances: A LitRPG Saga: Chaos Seeds, Book 3" to "The Land: Alliances: A LitRPG Saga",
                // Single-colon: that colon is the title:subtitle boundary, so we never strip across it —
                // only the ", Book N" comes off; the colon segment survives to become the subtitle.
                "The Girl Who Kicked the Hornet's Nest: The Millennium Series, Book 3" to
                    "The Girl Who Kicked the Hornet's Nest: The Millennium Series",
                "Dune: Messiah, Book 2" to "Dune: Messiah",
                "Catching Fire (Hunger Games, Book Two)" to "Catching Fire",
                "Mockingjay (The Hunger Games, Book Three)" to "Mockingjay",
                "The Hunger Games (The Hunger Games, Book One)" to "The Hunger Games",
                "Some Title (Some Series #4)" to "Some Title",
                "Wheel of Time, Volume 14" to "Wheel of Time",
            ).forEach { (input, expected) ->
                test("'$input' -> '$expected'") {
                    SeriesSuffixMatcher.stripTrailingSeriesSuffix(input) shouldBe expected
                }
            }
        }

        context("stripTrailingSeriesSuffix leaves false positives untouched") {
            listOf(
                "Myst - The Book of Atrus",
                "The Black Book of Power",
                "The New Big Book of Christian Mysticism",
                "The Land: Alliances: A LitRPG Saga",
                "Harry Potter and the Half-Blood Prince",
                "The Bunnicula Collection: Books 1-3",
                "Mistborn",
                // `Part N` is internal book structure, not a series position — never stripped.
                "It's a Wonderful Life, Part 2",
                "Notes from Underground, Part 4",
            ).forEach { input ->
                test("'$input' unchanged") {
                    SeriesSuffixMatcher.stripTrailingSeriesSuffix(input) shouldBe input
                }
            }
        }

        context("isSeriesReference detects a string that is really the series") {
            listOf(
                "Chaos Seeds, Book 3" to true,
                "The Millennium Series, Book 3" to true,
                "Book 3" to true,
                "(Hunger Games, Book Two)" to true,
                "Volume 14" to true,
                "A LitRPG Saga" to false,
                "Book of Atrus" to false,
                "An Essential Guide to Contemplative Spirituality" to false,
                // `Part` is excluded, so a "…, Part N" subtitle is not mistaken for a series reference.
                "A Memoir, Part Two" to false,
            ).forEach { (input, expected) ->
                test("isSeriesReference('$input') == $expected") {
                    SeriesSuffixMatcher.isSeriesReference(input) shouldBe expected
                }
            }
        }
    })
