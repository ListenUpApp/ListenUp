package com.calypsan.listenup.web.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/** Segments as the server plans 1-second targets at 44.1 kHz: whole ones of 44 frames, and a 0.4 s tail. */
private const val SEGMENT_SECONDS = "1.021678"
private const val SEGMENT_MS = 1_021.678
private const val TAIL_SECONDS = "0.400000"
private const val TAIL_MS = 400L

/** A short book: three whole segments and the tail. */
private const val SHORT_BOOK_SEGMENTS = 4

/**
 * Ten whole segments and the tail. A failure in the last second ends a book only past 90% of it
 * (`FinishedPolicy.acceptsEndedSignal`), so the end-of-book case needs a book over ten seconds long.
 */
private const val LONG_BOOK_SEGMENTS = 11

/** The long book plays at double speed, to keep the lane quick. */
private const val FAST = 2.0f

/** The fixture's last whole segment, and its 0.4 s tail. */
private const val FIXTURE_LAST_WHOLE = 2
private const val FIXTURE_TAIL = 3

/** Where the first refused segment begins: everything before it is audio the player already holds. */
private const val SECOND_SEGMENT_MS = 1_021L

/** Long enough for hls.js to have fetched ahead and for a whole segment to play out. */
private const val REFUSAL_TIMEOUT_MS = 20_000L

private const val SEEK_TARGET_MS = 2_500L

/** A beat after the failure for a reset element's `timeupdate` to arrive, if anything would publish it. */
private const val SETTLE_MS = 500L

/**
 * A book whose server refuses one segment for good — a 500 carrying `TranscodeFailed`, as the HLS
 * route answers once a segment has failed its frame check on every attempt (#1742).
 *
 * The refused segment comes from `vite.config.ts`'s `kotest-refused-segment` middleware; the good
 * ones are real ADTS AAC under `web/test/fixtures/hls/`, cut the way the server cuts them.
 */
class HtmlAudioPlayerRefusedSegmentTest :
    FunSpec({

        test("a refused segment ends in the server's refusal, after the audio before it has played") {
            val book = bookRefusing(segment = 1, segments = SHORT_BOOK_SEGMENTS)
            val player = HtmlAudioPlayer()
            player.load(listOf(book))
            player.play()

            val failed = player.awaitError()

            withClue("not a dropped connection with a Retry that can only loop") {
                failed.shouldBeInstanceOf<PlaybackError.CouldNotStart>()
            }
            failed.correlationId shouldBe "kotest-ref"
            withClue("hls.js fetches ahead; the segment it already held still played") {
                player.positionMs.value shouldBeGreaterThan SECOND_SEGMENT_MS - 200
            }

            // #1743: the torn-down element reads 0 and positions nothing.
            val where = player.positionMs.value
            delay(SETTLE_MS)
            player.positionMs.value shouldBe where
            player.seekTo(SEEK_TARGET_MS)
            withClue("a seek with nothing loaded behind it is not published") { player.positionMs.value shouldBe where }

            player.releasePlayer()
            URL.revokeObjectURL(book.hlsUrl!!)
        }

        test("a refused final segment shorter than a second finishes the book instead") {
            val book = bookRefusing(segment = LONG_BOOK_SEGMENTS - 1, segments = LONG_BOOK_SEGMENTS)
            val player = HtmlAudioPlayer()
            player.load(listOf(book))
            player.setSpeed(FAST)
            player.play()

            val settled =
                withTimeout(REFUSAL_TIMEOUT_MS) {
                    player.state.first { it == PlaybackState.Ended || it is PlaybackState.Error }
                }

            settled shouldBe PlaybackState.Ended
            player.positionMs.value shouldBeGreaterThan book.durationMs - 1_000

            player.releasePlayer()
            URL.revokeObjectURL(book.hlsUrl!!)
        }
    })

private suspend fun HtmlAudioPlayer.awaitError() =
    withTimeout(REFUSAL_TIMEOUT_MS) { state.first { it is PlaybackState.Error } }
        .let { (it as PlaybackState.Error).error }

/**
 * A book of [segments] segments (the last one the 0.4 s tail) as a VOD playlist in a blob, with segment
 * [segment] served as a refusal. Whole segments past the fixture's three reuse its second: ADTS carries
 * no timestamps, so hls.js places each by the playlist.
 */
private fun bookRefusing(
    segment: Int,
    segments: Int,
): AudioSegment {
    val origin = window.location.origin
    val playlist =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-TARGETDURATION:2")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            repeat(segments) { index ->
                val isTail = index == segments - 1
                appendLine("#EXTINF:${if (isTail) TAIL_SECONDS else SEGMENT_SECONDS},")
                val fixture = if (isTail) FIXTURE_TAIL else index.coerceAtMost(FIXTURE_LAST_WHOLE)
                appendLine(
                    if (index == segment) "$origin/kotest-refused-segment/seg$index.aac" else "$origin/test/fixtures/hls/seg$fixture.aac",
                )
            }
            append("#EXT-X-ENDLIST")
        }
    val url = URL.createObjectURL(Blob(arrayOf(playlist), BlobPropertyBag(type = "application/vnd.apple.mpegurl")))
    val durationMs = ((segments - 1) * SEGMENT_MS).toLong() + TAIL_MS
    return AudioSegment(url = url, hlsUrl = url, localPath = null, durationMs = durationMs, offsetMs = 0)
}
