package com.calypsan.listenup.client.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumePhraseDetectorTest {
    @Test
    fun `exact resume phrase returns true`() {
        assertTrue(ResumePhraseDetector.isResumeIntent("resume"))
        assertTrue(ResumePhraseDetector.isResumeIntent("continue"))
        assertTrue(ResumePhraseDetector.isResumeIntent("my audiobook"))
    }

    @Test
    fun `resume phrase with surrounding text returns true`() {
        assertTrue(ResumePhraseDetector.isResumeIntent("please resume my book"))
        assertTrue(ResumePhraseDetector.isResumeIntent("continue listening to my audiobook"))
    }

    @Test
    fun `case insensitive matching`() {
        assertTrue(ResumePhraseDetector.isResumeIntent("RESUME"))
        assertTrue(ResumePhraseDetector.isResumeIntent("Continue Listening"))
        assertTrue(ResumePhraseDetector.isResumeIntent("MY AUDIOBOOK"))
    }

    @Test
    fun `whitespace trimming`() {
        assertTrue(ResumePhraseDetector.isResumeIntent("  resume  "))
        assertTrue(ResumePhraseDetector.isResumeIntent("\tcontinue\n"))
    }

    @Test
    fun `specific book title returns false`() {
        assertFalse(ResumePhraseDetector.isResumeIntent("The Hobbit"))
        assertFalse(ResumePhraseDetector.isResumeIntent("play Mistborn"))
    }

    @Test
    fun `empty query returns false`() {
        assertFalse(ResumePhraseDetector.isResumeIntent(""))
        assertFalse(ResumePhraseDetector.isResumeIntent("   "))
    }

    @Test
    fun `all supported phrases are detected`() {
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
            assertTrue(ResumePhraseDetector.isResumeIntent(phrase), "Expected '$phrase' to be detected")
        }
    }
}
