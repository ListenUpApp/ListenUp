package com.calypsan.listenup.client.voice

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SeriesNavigationDetectorTest :
    FunSpec({
        test("next book patterns return Next") {
            SeriesNavigationDetector.detect("next book").shouldBeInstanceOf<SeriesNavigation.Next>()
            SeriesNavigationDetector.detect("the next one").shouldBeInstanceOf<SeriesNavigation.Next>()
            SeriesNavigationDetector.detect("next in series").shouldBeInstanceOf<SeriesNavigation.Next>()
            SeriesNavigationDetector.detect("play the next one").shouldBeInstanceOf<SeriesNavigation.Next>()
        }

        test("first book patterns return First") {
            SeriesNavigationDetector.detect("first book").shouldBeInstanceOf<SeriesNavigation.First>()
            SeriesNavigationDetector.detect("start of the series").shouldBeInstanceOf<SeriesNavigation.First>()
            SeriesNavigationDetector.detect("beginning").shouldBeInstanceOf<SeriesNavigation.First>()
        }

        test("numeric sequence returns BySequence") {
            val result = SeriesNavigationDetector.detect("book 2").shouldBeInstanceOf<SeriesNavigation.BySequence>()
            result.sequence shouldBe "2"
        }

        test("decimal sequence returns BySequence") {
            val result = SeriesNavigationDetector.detect("book 1.5").shouldBeInstanceOf<SeriesNavigation.BySequence>()
            result.sequence shouldBe "1.5"
        }

        test("word numbers return BySequence") {
            val testCases =
                listOf(
                    "second book" to "2",
                    "third book" to "3",
                    "fourth book" to "4",
                    "fifth book" to "5",
                )
            testCases.forEach { (input, expected) ->
                val result =
                    withClue("Expected BySequence for '$input'") {
                        SeriesNavigationDetector.detect(input).shouldBeInstanceOf<SeriesNavigation.BySequence>()
                    }
                withClue("Expected sequence '$expected' for '$input'") {
                    result.sequence shouldBe expected
                }
            }
        }

        test("case insensitive matching") {
            SeriesNavigationDetector.detect("NEXT BOOK").shouldBeInstanceOf<SeriesNavigation.Next>()
            SeriesNavigationDetector.detect("FIRST BOOK").shouldBeInstanceOf<SeriesNavigation.First>()

            val result = SeriesNavigationDetector.detect("SECOND BOOK").shouldBeInstanceOf<SeriesNavigation.BySequence>()
            result.sequence shouldBe "2"
        }

        test("unrelated query returns NotSeriesNavigation") {
            SeriesNavigationDetector.detect("The Hobbit").shouldBeInstanceOf<SeriesNavigation.NotSeriesNavigation>()
            SeriesNavigationDetector.detect("play something").shouldBeInstanceOf<SeriesNavigation.NotSeriesNavigation>()
            SeriesNavigationDetector.detect("").shouldBeInstanceOf<SeriesNavigation.NotSeriesNavigation>()
        }
    })
