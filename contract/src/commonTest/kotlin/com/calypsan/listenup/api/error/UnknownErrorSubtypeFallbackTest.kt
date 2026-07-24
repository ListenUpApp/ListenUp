package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The additive-wire guarantee on the **polymorphic** axis.
 *
 * `ignoreUnknownKeys = true` makes an old client tolerate new *fields*. It does nothing for new
 * *subtypes*: an unrecognised `type` discriminator in a sealed hierarchy throws
 * `SerializationException`. Without a registered polymorphic default, a client in the field that
 * meets an `AppError` family added after it shipped fails on the error path — i.e. hardest exactly
 * when something has already gone wrong.
 *
 * That makes every future error-taxonomy change wire-breaking, which is a decision we would rather
 * not make by accident. These tests pin the fallback.
 */
class UnknownErrorSubtypeFallbackTest :
    FunSpec({
        // A payload from a hypothetical future server: an AppError family this build has never
        // heard of. Shaped like every other AppError — the discriminator is the only unknown.
        val futureFamilyWire =
            """
            {"type":"AppError.SomeFutureFamily","correlationId":"cid-future-1",
             "code":"FUTURE_THING_FAILED","message":"A future thing failed.","isRetryable":true}
            """.trimIndent()

        test("an AppError subtype this build does not know decodes instead of throwing") {
            shouldNotThrowAny {
                contractJson.decodeFromString<AppError>(futureFamilyWire)
            }
        }

        test("the fallback preserves the correlation id so the operator can still join the log line") {
            val decoded = contractJson.decodeFromString<AppError>(futureFamilyWire)

            decoded.correlationId shouldBe "cid-future-1"
        }

        test("the fallback preserves the wire code rather than flattening it to a constant") {
            val decoded = contractJson.decodeFromString<AppError>(futureFamilyWire)

            decoded.code shouldBe "FUTURE_THING_FAILED"
        }

        test("the fallback names the family that arrived, so it is never silently anonymous") {
            val decoded = contractJson.decodeFromString<AppError>(futureFamilyWire)

            decoded.debugInfo shouldContain "AppError.SomeFutureFamily"
        }

        test("a known AppError family still decodes to its own type, not the fallback") {
            val known: AppError = InternalError(correlationId = "cid-known", cause = "IllegalStateException")

            val decoded = contractJson.decodeFromString<AppError>(contractJson.encodeToString(known))

            decoded.shouldBeInstanceOf<InternalError>()
            decoded shouldBe known
        }

        // Control probe (canon ch. 09): a rule must be shown to fail when unset, or it proves
        // nothing. Without the polymorphic default, the very same payload throws — which is
        // exactly the pre-fix behaviour this fallback exists to remove.
        test("WITHOUT the polymorphic default the same payload throws — the setting is load-bearing") {
            val noFallbackJson =
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }

            shouldThrow<SerializationException> {
                noFallbackJson.decodeFromString<AppError>(futureFamilyWire)
            }
        }
    })
