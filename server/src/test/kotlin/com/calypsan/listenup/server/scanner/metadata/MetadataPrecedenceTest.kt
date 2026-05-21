package com.calypsan.listenup.server.scanner.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MetadataPrecedenceTest :
    FunSpec({
        test("default precedence is metadata.json, embedded, sidecar, filename, folder") {
            MetadataPrecedence.DEFAULT.order shouldContainExactly
                listOf(
                    MetadataPrecedenceSource.ABS_METADATA,
                    MetadataPrecedenceSource.EMBEDDED,
                    MetadataPrecedenceSource.SIDECAR,
                    MetadataPrecedenceSource.FILENAME,
                    MetadataPrecedenceSource.FOLDER,
                )
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
    })
