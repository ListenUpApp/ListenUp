package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TransportError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

/**
 * Every failure type a playback path can meet, with the two answers [isCurableByNetwork] and [isServerOutOfReach]
 * give for it. A new `PlaybackError` or `TransportError` subtype fails the exhaustiveness checks below until it is
 * added here, so the question "can the network cure this?" is answered on purpose for every one.
 */
private data class Kind(
    val error: AppError,
    val isCurable: Boolean,
    val isOutOfReach: Boolean,
)

private val kinds: List<Kind> =
    listOf(
        Kind(PlaybackError.Stalled(), isCurable = true, isOutOfReach = false),
        Kind(PlaybackError.BlockedInBackground(), isCurable = false, isOutOfReach = false),
        Kind(PlaybackError.CouldNotStart(), isCurable = false, isOutOfReach = false),
        Kind(PlaybackError.ConnectionLost(), isCurable = true, isOutOfReach = false),
        Kind(PlaybackError.ServerUnreachable(), isCurable = true, isOutOfReach = true),
        Kind(PlaybackError.OfflineNotDownloaded(), isCurable = true, isOutOfReach = true),
        Kind(PlaybackError.UnsupportedFormat(), isCurable = false, isOutOfReach = false),
        Kind(PlaybackError.AutoplayBlocked(), isCurable = false, isOutOfReach = false),
        Kind(PlaybackError.NoLongerAvailable(), isCurable = false, isOutOfReach = false),
        Kind(TransportError.NetworkUnavailable(), isCurable = true, isOutOfReach = false),
        Kind(TransportError.Timeout(), isCurable = true, isOutOfReach = false),
        Kind(TransportError.OutcomeUnknown(), isCurable = false, isOutOfReach = false),
        Kind(TransportError.Server4xx(statusCode = 404), isCurable = false, isOutOfReach = false),
        Kind(TransportError.Server5xx(statusCode = 503), isCurable = true, isOutOfReach = false),
        Kind(TransportError.DataMalformed(detail = "x"), isCurable = false, isOutOfReach = false),
        Kind(TransportError.ContractMismatch(detail = "x"), isCurable = false, isOutOfReach = false),
        Kind(TransportError.ImageNotUploaded(), isCurable = false, isOutOfReach = false),
        Kind(TransportError.ImageUploadQueued(), isCurable = false, isOutOfReach = false),
        Kind(AuthError.SessionExpired(), isCurable = false, isOutOfReach = false),
        Kind(BookError.HeldForReview(), isCurable = false, isOutOfReach = false),
    )

private fun coveredSubtypesOf(parent: KClass<out AppError>): Set<KClass<*>> =
    kinds
        .map { it.error::class }
        .filter { type -> type.supertypes.any { supertype -> supertype.classifier == parent } }
        .toSet()

class PlaybackFailureKindsTest :
    FunSpec({
        test("the table covers every PlaybackError subtype") {
            coveredSubtypesOf(PlaybackError::class) shouldBe PlaybackError::class.sealedSubclasses.toSet()
        }

        test("the table covers every TransportError subtype") {
            coveredSubtypesOf(TransportError::class) shouldBe TransportError::class.sealedSubclasses.toSet()
        }

        kinds.forEach { kind ->
            test("${kind.error.code}: curable by network is ${kind.isCurable}") {
                kind.error.isCurableByNetwork() shouldBe kind.isCurable
            }
            test("${kind.error.code}: server out of reach is ${kind.isOutOfReach}") {
                kind.error.isServerOutOfReach() shouldBe kind.isOutOfReach
            }
        }

        test("no error at all is not out of reach") {
            (null as AppError?).isServerOutOfReach() shouldBe false
        }
    })
