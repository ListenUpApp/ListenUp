package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.api.dto.CodecCapability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The decision table. Every row here is a user outcome: a wrong `DirectPlay` is silence on someone's
 * phone, a wrong `Transcode` is CPU burned to re-encode a file that would have played.
 */
class TranscodePolicyTest :
    FunSpec({

        val policy = TranscodePolicy()
        val chromeish = setOf(CodecCapability.MP3, CodecCapability.AAC_LC)

        // ⛔ The legacy contract: no declaration means today's behaviour, exactly.
        test("a client that declares nothing direct-plays everything") {
            policy.decide(codec = "aac", profile = "xhe", capabilities = null, force = false, available = true) shouldBe
                TranscodeDecision.DirectPlay
        }

        test("a declared codec direct-plays") {
            policy.decide("aac", "lc", chromeish, force = false, available = true) shouldBe TranscodeDecision.DirectPlay
        }

        // The case this whole arc exists for.
        test("xHE-AAC transcodes for a client that cannot decode it") {
            policy.decide("aac", "xhe", chromeish, force = false, available = true) shouldBe TranscodeDecision.Transcode
        }

        test("force overrides a perfectly playable file") {
            policy.decide("mp3", null, chromeish, force = true, available = true) shouldBe TranscodeDecision.Transcode
        }

        // ⛔ Never Stranded: with no encoder we serve the original and let the client explain itself.
        test("no encoder means direct-play, flagged") {
            policy.decide("aac", "xhe", chromeish, force = false, available = false) shouldBe
                TranscodeDecision.DirectPlayTranscoderUnavailable
        }

        // An unknown profile is the 257 null-profile rows in a real library: assume the base codec.
        test("an unknown AAC profile is treated as AAC-LC") {
            policy.decide("aac", null, chromeish, force = false, available = true) shouldBe TranscodeDecision.DirectPlay
        }

        test("an unknown codec entirely is transcoded rather than gambled on") {
            policy.decide("ac4", null, chromeish, force = false, available = true) shouldBe TranscodeDecision.Transcode
        }
    })
