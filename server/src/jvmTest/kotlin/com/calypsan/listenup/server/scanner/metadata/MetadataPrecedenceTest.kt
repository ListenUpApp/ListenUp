package com.calypsan.listenup.server.scanner.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MetadataPrecedenceTest :
    FunSpec({
        test("default precedence is listenup.json, metadata.json, embedded, sidecar, filename, folder") {
            MetadataPrecedence.DEFAULT.order shouldContainExactly
                listOf(
                    MetadataPrecedenceSource.LISTENUP,
                    MetadataPrecedenceSource.ABS_METADATA,
                    MetadataPrecedenceSource.EMBEDDED,
                    MetadataPrecedenceSource.SIDECAR,
                    MetadataPrecedenceSource.FILENAME,
                    MetadataPrecedenceSource.FOLDER,
                )
        }
        test("parse accepts the listenup.json token") {
            MetadataPrecedence.parse("listenup.json,embedded").order shouldContainExactly
                listOf(MetadataPrecedenceSource.LISTENUP, MetadataPrecedenceSource.EMBEDDED)
        }
        test("parse reorders and disables") {
            MetadataPrecedence.parse("embedded,folder").order shouldContainExactly
                listOf(MetadataPrecedenceSource.EMBEDDED, MetadataPrecedenceSource.FOLDER)
        }
        test("parse rejects an unknown token") {
            shouldThrow<IllegalArgumentException> { MetadataPrecedence.parse("embedded,bogus") }
        }
        test("parse of blank yields the default") {
            MetadataPrecedence.parse("") shouldBe MetadataPrecedence.DEFAULT
        }

        test("resolveLibraryPrecedence: blank returns the fallback (not parse's DEFAULT)") {
            val fallback = MetadataPrecedence(listOf(MetadataPrecedenceSource.EMBEDDED))
            resolveLibraryPrecedence("", fallback) shouldBe fallback
            resolveLibraryPrecedence("   ", fallback) shouldBe fallback
        }

        test("resolveLibraryPrecedence: a valid token list is parsed") {
            val fallback = MetadataPrecedence.DEFAULT
            val raw = MetadataPrecedence(listOf(MetadataPrecedenceSource.SIDECAR, MetadataPrecedenceSource.EMBEDDED)).serialize()
            resolveLibraryPrecedence(raw, fallback) shouldBe
                MetadataPrecedence(listOf(MetadataPrecedenceSource.SIDECAR, MetadataPrecedenceSource.EMBEDDED))
        }

        test("resolveLibraryPrecedence: an unknown token falls back instead of throwing") {
            val fallback = MetadataPrecedence(listOf(MetadataPrecedenceSource.EMBEDDED))
            resolveLibraryPrecedence("embedded,not-a-real-source", fallback) shouldBe fallback
        }
    })
