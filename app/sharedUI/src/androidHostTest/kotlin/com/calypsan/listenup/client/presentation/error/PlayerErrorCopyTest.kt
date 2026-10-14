package com.calypsan.listenup.client.presentation.error

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull

/** Every error the player card can show has a title and a cause in the catalog (spec §4.6). */
class PlayerErrorCopyTest :
    FunSpec({
        val playerErrors: List<AppError> =
            listOf(
                PlaybackError.ConnectionLost(),
                PlaybackError.Stalled(),
                PlaybackError.CouldNotStart(),
                PlaybackError.ServerUnreachable(),
                PlaybackError.OfflineNotDownloaded(),
                PlaybackError.UnsupportedFormat(),
                AuthError.SessionExpired(),
                PlaybackError.NoLongerAvailable(),
                BookError.HeldForReview(),
                PlaybackError.BlockedInBackground(),
                PlaybackError.AutoplayBlocked(),
            )

        playerErrors.forEach { error ->
            test("${error.code} has a player title and cause") {
                error.playerTitleResource().shouldNotBeNull()
                error.playerCauseResource().shouldNotBeNull()
            }
        }
    })
