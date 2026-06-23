package com.calypsan.listenup.server.embeddedmeta.format.mp4

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudioSpecificConfigTest :
    FunSpec({
        // Pack AOT(5) | freqIdx(4) | chanCfg(4) MSB-first into bytes.
        fun asc(
            aot: Int,
            freqIdx: Int,
            chan: Int,
        ): ByteArray {
            var v = 0L
            var n = 0

            fun put(
                value: Int,
                bits: Int,
            ) {
                v = (v shl bits) or value.toLong()
                n += bits
            }
            if (aot >= 31) {
                put(31, 5)
                put(aot - 32, 6)
            } else {
                put(aot, 5)
            }
            put(freqIdx, 4)
            put(chan, 4)
            val totalBytes = (n + 7) / 8
            v = v shl totalBytes * 8 - n
            return ByteArray(totalBytes) { i -> ((v ushr (totalBytes - 1 - i) * 8) and 0xFF).toByte() }
        }

        test("AAC-LC (AOT 2), 44100 Hz, stereo") {
            val info = decodeAudioSpecificConfig(asc(2, 4, 2))
            info.audioObjectType shouldBe 2
            info.sampleRate shouldBe 44100
            info.channels shouldBe 2
        }
        test("HE-AAC (AOT 5)") { decodeAudioSpecificConfig(asc(5, 3, 2)).audioObjectType shouldBe 5 }
        test("HE-AACv2 (AOT 29)") { decodeAudioSpecificConfig(asc(29, 3, 1)).audioObjectType shouldBe 29 }
        test("xHE-AAC / USAC (AOT 42) decodes via the 5-bit escape, 48000 Hz") {
            val info = decodeAudioSpecificConfig(asc(42, 3, 2))
            info.audioObjectType shouldBe 42
            info.sampleRate shouldBe 48000
            info.channels shouldBe 2
        }
        test("channelConfiguration 7 maps to 8 channels") {
            decodeAudioSpecificConfig(asc(2, 3, 7)).channels shouldBe 8
        }
    })
