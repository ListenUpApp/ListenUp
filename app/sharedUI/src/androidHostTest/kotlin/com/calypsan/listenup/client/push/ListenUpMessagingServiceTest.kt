package com.calypsan.listenup.client.push

import androidx.lifecycle.Lifecycle
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.push.PushPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [decodePushPayload] and [shouldSuppressForeground] — the two decision points
 * [ListenUpMessagingService.onMessageReceived] delegates to. Both are pure (no Android
 * framework touched — [Lifecycle.State] is a plain enum, decoding is plain
 * kotlinx.serialization), so this is a plain Kotest [FunSpec] with no Robolectric runner,
 * unlike [PushNotificationRendererTest] which posts real framework [android.app.Notification]s.
 */
class ListenUpMessagingServiceTest :
    FunSpec({

        test("decodePushPayload returns the typed payload for valid RegistrationDecision JSON") {
            val original = PushPayload.RegistrationDecision(userId = "u1", approved = true)
            val json = contractJson.encodeToString(PushPayload.serializer(), original)

            decodePushPayload(mapOf("payload" to json)) shouldBe original
        }

        test("decodePushPayload returns null when the payload key is absent") {
            decodePushPayload(emptyMap()).shouldBeNull()
        }

        test("decodePushPayload returns null for an unknown discriminator") {
            decodePushPayload(mapOf("payload" to """{"type":"from_the_future"}""")).shouldBeNull()
        }

        test("decodePushPayload returns null for malformed JSON") {
            decodePushPayload(mapOf("payload" to "{not json")).shouldBeNull()
        }

        test("shouldSuppressForeground is true when the app is STARTED") {
            shouldSuppressForeground(Lifecycle.State.STARTED) shouldBe true
        }

        test("shouldSuppressForeground is true when the app is RESUMED") {
            shouldSuppressForeground(Lifecycle.State.RESUMED) shouldBe true
        }

        test("shouldSuppressForeground is false when the app is only CREATED") {
            shouldSuppressForeground(Lifecycle.State.CREATED) shouldBe false
        }
    })
