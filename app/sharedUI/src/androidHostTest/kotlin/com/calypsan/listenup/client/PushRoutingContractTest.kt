package com.calypsan.listenup.client

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.push.PushPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins `MainActivity`'s push-routing literal to the contract's actual wire discriminator.
 *
 * The two are necessarily different kinds of thing — by the time a tapped notification reaches
 * `MainActivity` its type is a `String` in an Intent extra, not a `PushPayload` — so the literal
 * cannot be derived from the sealed type at the point of use. That makes it exactly the kind of
 * duplicated constant that rots: rename the `@SerialName` and nothing fails to compile, nothing
 * fails at runtime, and tapping the notification silently stops routing anywhere. The admin would
 * simply land wherever the app was last, which is precisely the behaviour this replaced.
 */
class PushRoutingContractTest :
    FunSpec({
        test("MainActivity's registration-approval literal matches the payload's @SerialName") {
            val encoded =
                contractJson.encodeToString(
                    PushPayload.serializer(),
                    PushPayload.RegistrationApproval(userId = "u1"),
                )
            val discriminator =
                Json
                    .parseToJsonElement(encoded)
                    .jsonObject["type"]
                    ?.jsonPrimitive
                    ?.content

            discriminator shouldBe PUSH_TYPE_REGISTRATION_APPROVAL
        }
    })
