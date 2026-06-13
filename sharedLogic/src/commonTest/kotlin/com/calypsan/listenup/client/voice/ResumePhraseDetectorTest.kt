package com.calypsan.listenup.client.voice

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResumePhraseDetectorTest :
    FunSpec({
        test("exact resume phrase returns true") {
            ResumePhraseDetector.isResumeIntent("resume") shouldBe true
            ResumePhraseDetector.isResumeIntent("continue") shouldBe true
            ResumePhraseDetector.isResumeIntent("my audiobook") shouldBe true
        }

        test("resume phrase with surrounding text returns true") {
            ResumePhraseDetector.isResumeIntent("please resume my book") shouldBe true
            ResumePhraseDetector.isResumeIntent("continue listening to my audiobook") shouldBe true
        }

        test("case insensitive matching") {
            ResumePhraseDetector.isResumeIntent("RESUME") shouldBe true
            ResumePhraseDetector.isResumeIntent("Continue Listening") shouldBe true
            ResumePhraseDetector.isResumeIntent("MY AUDIOBOOK") shouldBe true
        }

        test("whitespace trimming") {
            ResumePhraseDetector.isResumeIntent("  resume  ") shouldBe true
            ResumePhraseDetector.isResumeIntent("\tcontinue\n") shouldBe true
        }

        test("specific book title returns false") {
            ResumePhraseDetector.isResumeIntent("The Hobbit") shouldBe false
            ResumePhraseDetector.isResumeIntent("play Mistborn") shouldBe false
        }

        test("empty query returns false") {
            ResumePhraseDetector.isResumeIntent("") shouldBe false
            ResumePhraseDetector.isResumeIntent("   ") shouldBe false
        }

        test("all supported phrases are detected") {
            val phrases =
                listOf(
                    "resume",
                    "continue",
                    "continue listening",
                    "continue reading",
                    "my audiobook",
                    "my book",
                    "where i left off",
                    "pick up where i left off",
                    "keep playing",
                    "keep listening",
                )
            phrases.forEach { phrase ->
                withClue("Expected '$phrase' to be detected") {
                    ResumePhraseDetector.isResumeIntent(phrase) shouldBe true
                }
            }
        }
    })
