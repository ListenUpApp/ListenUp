package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure-classifier tests for [controllerTrustOf].
 *
 * The Media3-aware companion [MediaSession.classifyController] is exercised end-to-end
 * when [PlaybackService] runs on a real device; the precedence rules live here so they
 * can be verified without a `MediaSession` runtime.
 */
class ControllerGatingTest :
    FunSpec({

        data class Case(
            val name: String,
            val isOwnApp: Boolean,
            val isAutoOrAutomotive: Boolean,
            val isMediaNotification: Boolean,
            val isTrusted: Boolean,
            val expected: ControllerTrust,
        )

        listOf(
            Case("own-app wins when all flags are set", true, true, true, true, ControllerTrust.OWN_APP),
            Case("own-app alone", true, false, false, false, ControllerTrust.OWN_APP),
            Case("auto-or-automotive wins over notification and trusted", false, true, true, true, ControllerTrust.AUTO_OR_AUTOMOTIVE),
            Case("auto-or-automotive alone", false, true, false, false, ControllerTrust.AUTO_OR_AUTOMOTIVE),
            Case("notification wins over trusted", false, false, true, true, ControllerTrust.MEDIA_NOTIFICATION),
            Case("notification alone", false, false, true, false, ControllerTrust.MEDIA_NOTIFICATION),
            Case("trusted-system alone", false, false, false, true, ControllerTrust.TRUSTED_SYSTEM),
            Case("nothing set falls through to UNKNOWN", false, false, false, false, ControllerTrust.UNKNOWN),
        ).forEach { case ->
            test("controllerTrustOf: ${case.name}") {
                controllerTrustOf(
                    isOwnApp = case.isOwnApp,
                    isAutoOrAutomotive = case.isAutoOrAutomotive,
                    isMediaNotification = case.isMediaNotification,
                    isTrusted = case.isTrusted,
                ) shouldBe case.expected
            }
        }
    })
