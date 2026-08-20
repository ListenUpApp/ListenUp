package com.calypsan.listenup.client.playback

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec

/**
 * Runs on every platform, and deliberately asserts nothing about the *contents* — the whole point of
 * the design is that each platform answers for itself rather than matching a table written here.
 *
 * What it pins is that the call **answers**. It sits on the play path, so a platform whose codec
 * enumeration throws would strand playback outright rather than merely mis-declare it. The Android
 * actual is the one that can genuinely fail this way: `MediaCodecList` throws under the unmocked
 * `android.jar` this lane runs against, and can fail on a real device with an unreadable codec list
 * too. Answering "nothing" there is correct — the server then transcodes, and the listener still
 * hears the book.
 *
 * The per-platform *contents* are asserted where an actual can really answer:
 * [PlatformCodecCapabilitiesJvmTest].
 */
class PlatformCodecCapabilitiesTest :
    FunSpec({

        test("every platform answers instead of throwing") {
            shouldNotThrowAny { platformCodecCapabilities() }
        }
    })
