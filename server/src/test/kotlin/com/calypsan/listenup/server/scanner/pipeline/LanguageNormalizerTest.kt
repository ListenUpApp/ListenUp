package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LanguageNormalizerTest :
    FunSpec({
        test("ISO 639-1 passes through") {
            LanguageNormalizer.normalize("en") shouldBe "en"
            LanguageNormalizer.normalize("EN") shouldBe "en"
        }
        test("ISO 639-2 maps to 639-1, including bibliographic alternates") {
            LanguageNormalizer.normalize("eng") shouldBe "en"
            LanguageNormalizer.normalize("ger") shouldBe "de"
            LanguageNormalizer.normalize("fre") shouldBe "fr"
        }
        test("locale codes use the first part") {
            LanguageNormalizer.normalize("en-US") shouldBe "en"
            LanguageNormalizer.normalize("en_GB") shouldBe "en"
            LanguageNormalizer.normalize("pt-BR") shouldBe "pt"
        }
        test("language names map case-insensitively") {
            LanguageNormalizer.normalize("English") shouldBe "en"
            LanguageNormalizer.normalize("ENGLISH") shouldBe "en"
            LanguageNormalizer.normalize("Spanish") shouldBe "es"
            LanguageNormalizer.normalize("Mandarin") shouldBe "zh"
        }
        test("unrecognized and blank return null") {
            LanguageNormalizer.normalize("klingon") shouldBe null
            LanguageNormalizer.normalize("") shouldBe null
            LanguageNormalizer.normalize("   ") shouldBe null
        }
    })
