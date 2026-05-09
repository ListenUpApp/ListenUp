package com.calypsan.listenup.api.streaming

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.builtins.serializer

class RpcEventContractTest :
    FunSpec({

        test("Data variant round-trips through JSON") {
            val original: RpcEvent<String> = RpcEvent.Data("hello")
            val json =
                contractJson.encodeToString(
                    RpcEvent.serializer(String.serializer()),
                    original,
                )
            val decoded =
                contractJson.decodeFromString(
                    RpcEvent.serializer(String.serializer()),
                    json,
                )
            decoded.shouldBeInstanceOf<RpcEvent.Data<String>>()
            (decoded as RpcEvent.Data<String>).value shouldBe "hello"
        }

        test("Error variant round-trips through JSON with typed AppError payload") {
            val err: AppError = InternalError(correlationId = "xyz", cause = "NPE")
            val original: RpcEvent<String> = RpcEvent.Error(err)
            val json =
                contractJson.encodeToString(
                    RpcEvent.serializer(String.serializer()),
                    original,
                )
            val decoded =
                contractJson.decodeFromString(
                    RpcEvent.serializer(String.serializer()),
                    json,
                )
            decoded.shouldBeInstanceOf<RpcEvent.Error>()
            (decoded as RpcEvent.Error).error.shouldBeInstanceOf<InternalError>()
            ((decoded.error) as InternalError).correlationId shouldBe "xyz"
        }

        test("Complete variant round-trips through JSON") {
            val original: RpcEvent<String> = RpcEvent.Complete
            val json =
                contractJson.encodeToString(
                    RpcEvent.serializer(String.serializer()),
                    original,
                )
            val decoded =
                contractJson.decodeFromString(
                    RpcEvent.serializer(String.serializer()),
                    json,
                )
            decoded shouldBe RpcEvent.Complete
        }

        test("SerialName discriminators are stable") {
            val data: RpcEvent<String> = RpcEvent.Data("x")
            val err: RpcEvent<String> = RpcEvent.Error(InternalError())
            val complete: RpcEvent<String> = RpcEvent.Complete
            contractJson.encodeToString(
                RpcEvent.serializer(String.serializer()),
                data,
            ) shouldBe """{"type":"RpcEvent.Data","value":"x"}"""
            contractJson.encodeToString(
                RpcEvent.serializer(String.serializer()),
                complete,
            ) shouldBe """{"type":"RpcEvent.Complete"}"""
            // Error wraps a polymorphic AppError; assert the outer discriminator only.
            val errJson =
                contractJson.encodeToString(
                    RpcEvent.serializer(String.serializer()),
                    err,
                )
            errJson.startsWith("""{"type":"RpcEvent.Error"""") shouldBe true
        }
    })
