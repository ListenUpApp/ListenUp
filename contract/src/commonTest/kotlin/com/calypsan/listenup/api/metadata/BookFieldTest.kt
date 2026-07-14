package com.calypsan.listenup.api.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BookFieldTest :
    FunSpec({
        test("authors and narrators both live in the CONTRIBUTORS domain yet stay distinct fields") {
            // The flagship field-routing case: an operator can point AUTHORS at one
            // provider and NARRATORS at another even though both share a domain.
            BookField.AUTHORS.domain shouldBe MetadataDomain.CONTRIBUTORS
            BookField.NARRATORS.domain shouldBe MetadataDomain.CONTRIBUTORS
            BookField.AUTHORS shouldNotBe BookField.NARRATORS
        }

        test("every book-core text field maps to BOOK_CORE") {
            listOf(
                BookField.TITLE,
                BookField.SUBTITLE,
                BookField.DESCRIPTION,
                BookField.PUBLISHER,
                BookField.PUBLISH_YEAR,
                BookField.LANGUAGE,
            ).forEach { it.domain shouldBe MetadataDomain.BOOK_CORE }
        }

        test("genre-family fields all map to the GENRES domain") {
            listOf(BookField.GENRES, BookField.MOODS, BookField.TAGS)
                .forEach { it.domain shouldBe MetadataDomain.GENRES }
        }

        test("single-field domains map one-to-one") {
            BookField.SERIES.domain shouldBe MetadataDomain.SERIES
            BookField.COVER.domain shouldBe MetadataDomain.COVER
            BookField.CHAPTERS.domain shouldBe MetadataDomain.CHAPTERS
        }

        test("every field has a lowercase token that round-trips through fromToken") {
            BookField.entries.forEach { field ->
                field.token shouldBe field.name.lowercase()
                BookField.fromToken(field.token) shouldBe field
                BookField.fromToken(field.token.uppercase()) shouldBe field
            }
        }

        test("fromToken returns null for an unknown token") {
            BookField.fromToken("not_a_field") shouldBe null
        }

        test("every MetadataDomain except CHARACTERS has at least one field routing into it") {
            val covered = BookField.entries.map { it.domain }.toSet()
            MetadataDomain.entries.forEach { domain ->
                covered.contains(domain) shouldBe (domain != MetadataDomain.CHARACTERS)
            }
        }

        test("domain tokens round-trip through fromToken and reject the enum name") {
            MetadataDomain.entries.forEach { domain ->
                MetadataDomain.fromToken(domain.token) shouldBe domain
            }
            MetadataDomain.fromToken("book_core") shouldBe null
            MetadataDomain.fromToken("core") shouldBe MetadataDomain.BOOK_CORE
        }
    })
