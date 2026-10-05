package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun payload(
    externalRefs: List<ExternalRef> = emptyList(),
    releaseDate: String? = null,
) = BookSyncPayload(
    id = "book-1",
    libraryId = LibraryId("lib-1"),
    folderId = FolderId("folder-1"),
    title = "Project Hail Mary",
    sortTitle = null,
    subtitle = null,
    description = null,
    publishYear = 2021,
    publisher = null,
    language = null,
    isbn = null,
    asin = "B08G9PRS1K",
    abridged = false,
    explicit = false,
    totalDuration = 1_000L,
    cover = null,
    rootRelPath = "Weir/Project Hail Mary",
    inode = null,
    scannedAt = 0L,
    contributors = emptyList(),
    series = emptyList(),
    audioFiles = emptyList(),
    chapters = emptyList(),
    revision = 1L,
    updatedAt = 0L,
    createdAt = 0L,
    deletedAt = null,
    externalRefs = externalRefs,
    releaseDate = releaseDate,
)

/** Strips top-level `"key":<value>` entries — what a server that predates them would have sent. */
private fun String.withoutKeys(vararg keys: String): String =
    keys.fold(this) { json, key -> json.replace(Regex(""","$key":(null|"[^"]*"|\[[^\]]*\])"""), "") }

/**
 * Provider-neutral identity and the full release date on the book payload, plus who made a hand
 * edit. Every field is additive and defaulted, so a payload from an older server still decodes.
 */
class BookIdentityWireTest :
    FunSpec({
        test("catalogue refs and the full release date round-trip") {
            val v =
                payload(
                    externalRefs = listOf(ExternalRef("audible", "B08G9PRS1K", region = "us"), ExternalRef("hardcover", "428")),
                    releaseDate = "2021-05-04",
                )
            contractJson.decodeFromString<BookSyncPayload>(contractJson.encodeToString(v)) shouldBe v
        }

        test("a payload from a server that predates refs and release dates still decodes") {
            val legacy =
                contractJson
                    .encodeToString(payload(externalRefs = listOf(ExternalRef("audible", "B08G9PRS1K")), releaseDate = "2021-05-04"))
                    .withoutKeys("externalRefs", "releaseDate")
            val v = contractJson.decodeFromString<BookSyncPayload>(legacy)
            v.externalRefs shouldBe emptyList()
            v.releaseDate shouldBe null
        }

        test("a hand edit says who made it, and an older stamp without one still decodes") {
            val stamp = FieldProvenance(FieldSourceKind.USER, at = 7L, by = "user-1")
            contractJson.decodeFromString<FieldProvenance>(contractJson.encodeToString(stamp)) shouldBe stamp
            contractJson.decodeFromString<FieldProvenance>("""{"kind":"USER","at":7}""").by shouldBe null
        }

        test("a ref needs a provider and a key") {
            shouldThrow<IllegalArgumentException> { ExternalRef("", "B08G9PRS1K") }
            shouldThrow<IllegalArgumentException> { ExternalRef("audible", " ") }
        }
    })
