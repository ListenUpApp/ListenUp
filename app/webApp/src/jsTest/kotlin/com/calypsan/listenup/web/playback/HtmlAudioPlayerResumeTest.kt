package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeBetween
import kotlinx.coroutines.delay
import org.w3c.dom.url.URL

private const val SEGMENT_DURATION_MS = 1_500L
private const val RESUME_MS = 750L

/** A seek lands on a sample boundary, not a millisecond one; ±100 ms is far below the 750 ms gap. */
private const val TOLERANCE_MS = 100L

private const val SAMPLE_COUNT = 10
private const val SAMPLE_INTERVAL_MS = 50L

/**
 * Losing a listener's place is the one failure this app cannot afford, and `load()` followed by
 * `seekTo()` — what `PlaybackManagerImpl` does on every resume — is the path it travels.
 *
 * For a single-file `.m4b`, which is most audiobooks, that seek stays inside segment 0. A version
 * of this player recorded the resume offset only on attach, so the same-segment branch left the
 * recorded value at zero and `loadedmetadata` rewound the book to the beginning — after
 * `positionMs` had already reported the correct place, which is the worst kind of wrong.
 */
class HtmlAudioPlayerResumeTest :
    FunSpec({

        test("a resume inside the first segment survives the element becoming ready") {
            val segment = silentSegment(SEGMENT_DURATION_MS)
            val player = HtmlAudioPlayer()
            player.load(listOf(segment))

            player.seekTo(RESUME_MS)

            // The regression fired at `loadedmetadata`, so a spec that finished before the element
            // got there would pass without ever entering the window it guards. Waiting for `Paused`
            // proves the element reached `canplay`, which is strictly past `loadedmetadata` — and
            // times out loudly rather than passing vacuously if it never does.
            player.awaitState(PlaybackState.Paused)
            player.positionMs.value.shouldBeBetween(RESUME_MS - TOLERANCE_MS, RESUME_MS + TOLERANCE_MS)

            // Nothing may quietly move it afterwards either.
            repeat(SAMPLE_COUNT) {
                delay(SAMPLE_INTERVAL_MS)
                player.positionMs.value.shouldBeBetween(RESUME_MS - TOLERANCE_MS, RESUME_MS + TOLERANCE_MS)
            }

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }
    })
