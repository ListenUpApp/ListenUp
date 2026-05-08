package com.calypsan.listenup.domain.embeddedmeta

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class AudioFormatTest :
    FunSpec({
        val json = Json { useArrayPolymorphism = false }

        test("Mp3 round-trips through JSON") {
            val encoded = json.encodeToString(AudioFormat.serializer(), AudioFormat.Mp3)
            val decoded = json.decodeFromString(AudioFormat.serializer(), encoded)
            decoded shouldBe AudioFormat.Mp3
        }

        test("all five subtypes are distinct") {
            val all =
                setOf(
                    AudioFormat.Mp3,
                    AudioFormat.Flac,
                    AudioFormat.Mp4,
                    AudioFormat.Ogg,
                    AudioFormat.Opus,
                )
            all.size shouldBe 5
        }
    })
