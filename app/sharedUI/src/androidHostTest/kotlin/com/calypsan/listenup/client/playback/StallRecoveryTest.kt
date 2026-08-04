package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import androidx.media3.common.Player
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Route

private val passThroughInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }

private val giveUpAuthenticator = Authenticator { _: Route?, _ -> null }

/**
 * Guards the two bounds that stop a stalled stream from stranding a listener mid-book.
 *
 * Both exist because of a real incident: a stream stalled mid-walk and the player sat in
 * `STATE_BUFFERING` indefinitely. The read timeout was infinite, so OkHttp never raised an
 * `IOException`, so ExoPlayer never raised a `PlaybackException`, so [PlaybackErrorHandler]'s
 * network-retry path — which was correct all along — was unreachable. Silence, not failure,
 * was the bug.
 */
class StallRecoveryTest :
    FunSpec({

        test("every streaming socket timeout is finite so a half-open connection raises an error") {
            val client = buildStreamingHttpClient(passThroughInterceptor, giveUpAuthenticator)

            // The read timeout is the one that regressed: `readTimeout(0)` means "block forever",
            // which converts a dead socket into an eternal spinner instead of a retryable failure.
            client.readTimeoutMillis shouldBeGreaterThan 0
            client.connectTimeoutMillis shouldBeGreaterThan 0
            client.writeTimeoutMillis shouldBeGreaterThan 0
        }

        test("streaming timeouts match the declared budget") {
            val client = buildStreamingHttpClient(passThroughInterceptor, giveUpAuthenticator)

            client.readTimeoutMillis shouldBe STREAM_READ_TIMEOUT_MS
            client.connectTimeoutMillis shouldBe STREAM_CONNECT_TIMEOUT_MS
            client.writeTimeoutMillis shouldBe STREAM_WRITE_TIMEOUT_MS
        }

        test("the streaming client carries the auth interceptor and authenticator") {
            val client = buildStreamingHttpClient(passThroughInterceptor, giveUpAuthenticator)

            client.interceptors shouldBe listOf(passThroughInterceptor)
            client.authenticator shouldBe giveUpAuthenticator
        }

        test("the stuck-buffering backstop fires well inside Media3's ten-minute default") {
            // ExoPlayer.Builder.DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS is 600_000. Ten
            // minutes of spinner is abandonment, not a backstop.
            STUCK_BUFFERING_TIMEOUT_MS shouldBeLessThan 600_000
        }

        test("an idle player must be re-prepared before play, or the manual retry does nothing") {
            // The state a terminal error — or the recovery stop/prepare cycle — leaves behind.
            needsPrepareBeforePlay(Player.STATE_IDLE) shouldBe true
        }

        test("a player that is already going is not re-prepared") {
            // Re-preparing a healthy player would restart the current item, so the check has to
            // be exact rather than "anything that isn't READY".
            needsPrepareBeforePlay(Player.STATE_READY) shouldBe false
            needsPrepareBeforePlay(Player.STATE_BUFFERING) shouldBe false
            needsPrepareBeforePlay(Player.STATE_ENDED) shouldBe false
        }

        test("the stuck-buffering backstop leaves room for a read timeout plus ExoPlayer retries") {
            // Firing while a legitimate retry is still in flight would turn a recoverable blip
            // into a user-visible error, so the backstop must clear one full read timeout and the
            // retry/backoff window that follows it — not merely exceed the read timeout itself.
            STUCK_BUFFERING_TIMEOUT_MS shouldBeGreaterThan (STREAM_READ_TIMEOUT_MS * 2)
        }
    })
