package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.core.GenreId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips [BookGenreInput] through [contractJson]. Catches field-name drift
 * and [GenreId] value-class encoding regression.
 */
class BookGenreInputContractTest :
    FunSpec({

        test("should round-trip BookGenreInput with a GenreId") {
            val original = BookGenreInput(genreId = GenreId("g-fantasy"))
            roundTrip<BookGenreInput>(original) shouldBe original
            roundTrip<BookGenreInput>(original).genreId shouldBe GenreId("g-fantasy")
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
