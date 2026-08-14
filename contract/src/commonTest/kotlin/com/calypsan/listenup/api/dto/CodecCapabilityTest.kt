package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.builtins.SetSerializer

class CodecCapabilityTest :
    FunSpec({

        // The vocabulary has to cover every codec/profile pair the library actually holds, or a
        // client can never declare the thing it can decode.
        test("covers every profile the extractor produces") {
            CodecCapability.entries.map { it.name } shouldBe
                listOf("MP3", "AAC_LC", "AAC_HE", "AAC_HE_V2", "AAC_XHE", "FLAC", "OPUS", "VORBIS", "ALAC", "AC4")
        }

        test("round-trips through the contract's JSON") {
            val encoded =
                contractJson.encodeToString(SetSerializer(CodecCapability.serializer()), setOf(CodecCapability.AAC_XHE))

            encoded shouldContain "aac_xhe"
            contractJson.decodeFromString(SetSerializer(CodecCapability.serializer()), encoded) shouldBe
                setOf(CodecCapability.AAC_XHE)
        }

        // ⛔ The legacy-client guarantee: a client that says nothing must get exactly today's bytes.
        test("prepare defaults to declaring nothing") {
            val prepared = PreparedPlayback(bookId = "b1", audioFiles = emptyList(), resumePosition = null)

            prepared.transcodeUnavailable shouldBe false
        }
    })
