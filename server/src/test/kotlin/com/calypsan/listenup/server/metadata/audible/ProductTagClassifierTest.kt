package com.calypsan.listenup.server.metadata.audible

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProductTagClassifierTest :
    FunSpec({
        test("mood tags become moods; theme tags become tags, dropping genre/social/editor types") {
            val tags =
                listOf(
                    ProductTag("mood", "Feel-Good"),
                    ProductTag("mood", "Scary"),
                    ProductTag("mood", "Witty"),
                    ProductTag("theme", "Fantasy"),
                    ProductTag("theme", "Fiction"),
                    ProductTag("theme", "LitRPG"),
                    ProductTag("theme", "Survival"),
                    ProductTag("theme", "Game"),
                    ProductTag("genre", "Science Fiction & Fantasy"),
                    ProductTag("social_media", "BookTok"),
                    ProductTag("audible_editors", "Editor's Pick"),
                )

            val classified =
                ProductTagClassifier.classify(
                    tags = tags,
                    appliedGenreSlugs = setOf("fantasy", "fiction", "litrpg"),
                )

            classified.moods shouldBe listOf("Feel-Good", "Scary", "Witty")
            // Fantasy/Fiction/LitRPG excluded — already applied as genres; survivors stay tags.
            classified.tags shouldBe listOf("Survival", "Game")
        }

        test("excludes a theme by its ALIAS-aware canonical slug, not its raw name (C3)") {
            // "Sci-Fi" canonicalizes to slug `science-fiction`; the book has that genre applied,
            // so the Sci-Fi theme must be excluded even though its raw slug would be `sci-fi`.
            // A non-genre theme (Survival) survives — proving the exclusion is slug-based, not blanket.
            val tags =
                listOf(
                    ProductTag("theme", "Sci-Fi"),
                    ProductTag("theme", "Survival"),
                )

            val classified =
                ProductTagClassifier.classify(
                    tags = tags,
                    appliedGenreSlugs = setOf("science-fiction"),
                )

            classified.moods shouldBe emptyList()
            classified.tags shouldBe listOf("Survival")
        }
    })
