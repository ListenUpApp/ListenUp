package com.calypsan.listenup.client.push

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.push.PushPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [decodePushPayload] — the decode half of what
 * [ListenUpMessagingService.onMessageReceived] delegates to. Pure (no Android framework touched:
 * decoding is plain kotlinx.serialization), so this is a plain Kotest [FunSpec] with no
 * Robolectric runner, unlike [PushNotificationRendererTest] which posts real framework
 * [android.app.Notification]s. The render/suppress half is [PushForegroundPolicy]'s, covered by
 * [PushForegroundPolicyTest] — it decides on the decoded payload too, since a test notification is
 * exempt from foreground suppression.
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
    })
