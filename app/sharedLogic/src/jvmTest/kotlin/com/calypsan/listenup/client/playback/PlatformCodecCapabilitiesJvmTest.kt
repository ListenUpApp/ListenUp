package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.CodecCapability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * The contents assertions, on the one lane whose actual can answer them for real.
 *
 * `PlatformCodecCapabilitiesTest` covers every platform but can only assert that the call answers —
 * Android's actual reads a device codec list that does not exist in a unit test. The JVM actual
 * needs no device, so this is where "a platform declares something, and it includes MP3" is pinned.
 * MP3 is the one format every platform in this project decodes.
 */
class PlatformCodecCapabilitiesJvmTest :
    FunSpec({

        test("the desktop actual declares at least one codec") {
            platformCodecCapabilities().shouldNotBeEmpty()
        }

        test("the desktop actual decodes MP3") {
            platformCodecCapabilities() shouldContain CodecCapability.MP3
        }
    })
