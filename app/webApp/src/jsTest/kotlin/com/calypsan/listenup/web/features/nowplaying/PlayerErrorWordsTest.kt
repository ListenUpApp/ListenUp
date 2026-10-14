package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerErrorWordsTest :
    FunSpec({
        test("the web says server unreachable in browser terms") {
            PlaybackError.ServerUnreachable().playerCause() shouldBe "Books stream from your server in the browser."
            PlaybackError.ServerUnreachable().playerTitle() shouldBe "Can't reach your server."
        }
        test("connection failures share one title") {
            PlaybackError.ConnectionLost().playerTitle() shouldBe "Couldn't play this book."
            PlaybackError.Stalled().playerTitle() shouldBe "Couldn't play this book."
            PlaybackError.CouldNotStart().playerCause() shouldBe "Something went wrong starting this book."
        }
        test("held, revoked, session and autoplay read as the table says") {
            BookError.HeldForReview().playerTitle() shouldBe "Waiting in the Inbox."
            PlaybackError.NoLongerAvailable().playerTitle() shouldBe "No longer in your library."
            AuthError.SessionExpired().playerTitle() shouldBe "Your session expired. Please sign in again."
            PlaybackError.AutoplayBlocked().playerTitle() shouldBe "Tap Play to start."
        }
        test("an error the player has no words for falls back to its own message") {
            AuthError.RateLimited(retryAfterSeconds = 3).playerTitle() shouldBe AuthError.RateLimited(retryAfterSeconds = 3).message
        }
    })
