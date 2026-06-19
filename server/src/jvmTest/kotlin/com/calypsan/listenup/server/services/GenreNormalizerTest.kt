package com.calypsan.listenup.server.services

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GenreNormalizerTest :
    FunSpec({
        test("common Audible variations resolve to canonical taxonomy slugs") {
            GenreNormalizer.normalizeToSlugs("Sci-Fi") shouldBe listOf("science-fiction")
            GenreNormalizer.normalizeToSlugs("Science Fiction") shouldBe listOf("science-fiction")
            GenreNormalizer.normalizeToSlugs("Biographies & Memoirs") shouldBe listOf("biography-memoir")
            GenreNormalizer.normalizeToSlugs("Comedy & Humor") shouldBe listOf("humor")
        }
        test("an unmapped string returns its bare slug") {
            GenreNormalizer.normalizeToSlugs("Quantum Basket Weaving") shouldBe listOf("quantum-basket-weaving")
        }
        test("blank or symbol-only input returns empty") {
            GenreNormalizer.normalizeToSlugs("   ") shouldBe emptyList()
            GenreNormalizer.normalizeToSlugs("!!!") shouldBe emptyList()
        }
    })
