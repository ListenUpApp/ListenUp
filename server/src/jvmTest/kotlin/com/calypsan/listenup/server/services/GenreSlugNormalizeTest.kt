package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [GenreSlug.normalize].
 *
 * Mirrors `TagSlug` semantics: NFKD diacritic strip → lowercase → `&` → ` and `
 * → non-alphanum runs → `-` → collapse + trim. The `& → and` substitution is
 * deliberate: it produces canonical slugs such as `"sword-and-sorcery"` that no
 * other pipeline reproduces.
 */
class GenreSlugNormalizeTest :
    FunSpec({

        test("slugifies 'Epic Fantasy' to 'epic-fantasy'") {
            GenreSlug.normalize("Epic Fantasy") shouldBe AppResult.Success("epic-fantasy")
        }

        test("preserves 'LitRPG' as 'litrpg'") {
            GenreSlug.normalize("LitRPG") shouldBe AppResult.Success("litrpg")
        }

        test("maps 'Sci-Fi & Fantasy' to 'sci-fi-and-fantasy' via ampersand substitution") {
            GenreSlug.normalize("Sci-Fi & Fantasy") shouldBe AppResult.Success("sci-fi-and-fantasy")
        }

        test("strips diacritics from 'Café Noir'") {
            GenreSlug.normalize("Café Noir") shouldBe AppResult.Success("cafe-noir")
        }

        test("collapses repeated hyphens in 'Sci---Fi'") {
            GenreSlug.normalize("Sci---Fi") shouldBe AppResult.Success("sci-fi")
        }

        test("empty input returns InvalidInput") {
            val result = GenreSlug.normalize("")
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
        }

        test("whitespace-only input returns InvalidInput") {
            val result = GenreSlug.normalize("   ")
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
        }

        test("input that normalizes to empty (all punctuation) returns InvalidInput") {
            val result = GenreSlug.normalize("!!!")
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
        }
    })
