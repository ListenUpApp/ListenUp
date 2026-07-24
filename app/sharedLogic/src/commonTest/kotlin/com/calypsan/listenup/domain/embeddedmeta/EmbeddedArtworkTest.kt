package com.calypsan.listenup.domain.embeddedmeta

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json

class EmbeddedArtworkTest :
    FunSpec({
        val json = Json {}

        test("two EmbeddedArtwork values with same bytes compare equal") {
            val a = EmbeddedArtwork("image/jpeg", byteArrayOf(1, 2, 3, 4))
            val b = EmbeddedArtwork("image/jpeg", byteArrayOf(1, 2, 3, 4))
            a shouldBe b
            a.hashCode() shouldBe b.hashCode()
        }

        test("differing bytes compare unequal") {
            val a = EmbeddedArtwork("image/jpeg", byteArrayOf(1, 2, 3, 4))
            val b = EmbeddedArtwork("image/jpeg", byteArrayOf(1, 2, 3, 5))
            a shouldNotBe b
        }

        test("differing mime compares unequal") {
            val a = EmbeddedArtwork("image/jpeg", byteArrayOf(1, 2, 3, 4))
            val b = EmbeddedArtwork("image/png", byteArrayOf(1, 2, 3, 4))
            a shouldNotBe b
        }

        test("round-trips through JSON") {
            val artwork = EmbeddedArtwork("image/png", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            val decoded =
                json.decodeFromString(
                    EmbeddedArtwork.serializer(),
                    json.encodeToString(EmbeddedArtwork.serializer(), artwork),
                )
            decoded shouldBe artwork
        }
    })
