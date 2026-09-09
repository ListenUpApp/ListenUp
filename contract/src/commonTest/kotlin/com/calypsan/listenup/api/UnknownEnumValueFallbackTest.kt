package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The additive-wire guarantee on the **enum-value** axis.
 *
 * `ignoreUnknownKeys` makes an old build tolerate new *fields*, and the polymorphic default makes it
 * tolerate new *subtypes*. Neither covers a new enum *literal*: an unrecognised member name throws
 * `SerializationException`. That made every additive enum member wire-breaking — one new member on a
 * newer server froze the affected sync domain on every older client, because a catch-up page that
 * cannot decode never advances its cursor and the next pass re-fetches the identical page.
 *
 * `coerceInputValues` closes it, but only where the author wrote down a fallback: coercion can
 * substitute a property's declared default and nothing else. These tests pin both halves — the
 * fallback fires for an unknown literal, and a known literal is still decoded as itself.
 */
class UnknownEnumValueFallbackTest :
    FunSpec({
        // Payloads from a hypothetical newer server: the shape is right, one enum literal names a
        // member this build has never heard of.
        val futureScanIssue =
            """{"id":"i1","rootRelPath":"Author/Book","reason":"QUANTUM_TAG_COLLAPSE","firstSeenAt":1,"lastSeenAt":2}"""
        val futureFileEntry =
            """{"relPath":"a/b.holo","name":"b.holo","ext":"holo","size":10,"mtimeMs":20,"fileType":"HOLOGRAM"}"""
        val futureShare =
            """
            {"id":"s1","collectionId":"c1","sharedWithUserId":"u1","sharedByUserId":"u2",
             "permission":"curate","revision":1,"updatedAt":2}
            """.trimIndent()
        val futureCover = """{"source":"HOLOGRAPHIC","hash":"abc123"}"""

        test("an unknown ScanIssue reason lands on the declared UNKNOWN fallback instead of throwing") {
            shouldNotThrowAny {
                contractJson.decodeFromString<ScanIssue>(futureScanIssue)
            }

            contractJson.decodeFromString<ScanIssue>(futureScanIssue).reason shouldBe ScanIssueReason.UNKNOWN
        }

        test("an unknown FileEntry fileType lands on the declared UNKNOWN fallback") {
            contractJson.decodeFromString<FileEntry>(futureFileEntry).fileType shouldBe FileType.UNKNOWN
        }

        test("an unknown share permission lands on Read — least privilege, never an accidental grant") {
            contractJson.decodeFromString<CollectionShareSyncPayload>(futureShare).permission shouldBe SharePermission.Read
        }

        test("an unknown cover source lands on null — CoverSource has no honest fallback member") {
            contractJson.decodeFromString<CoverPayload>(futureCover).source shouldBe null
        }

        test("a KNOWN literal still decodes to its own member — the fallback is not swallowing valid input") {
            val knownScanIssue = futureScanIssue.replace("QUANTUM_TAG_COLLAPSE", "FILE_UNREADABLE")
            val knownFileEntry = futureFileEntry.replace("HOLOGRAM", "AUDIO")
            val knownShare = futureShare.replace("curate", "write")
            val knownCover = futureCover.replace("HOLOGRAPHIC", "UPLOADED")

            contractJson.decodeFromString<ScanIssue>(knownScanIssue).reason shouldBe ScanIssueReason.FILE_UNREADABLE
            contractJson.decodeFromString<FileEntry>(knownFileEntry).fileType shouldBe FileType.AUDIO
            contractJson.decodeFromString<CollectionShareSyncPayload>(knownShare).permission shouldBe SharePermission.Write
            contractJson.decodeFromString<CoverPayload>(knownCover).source shouldBe CoverSource.UPLOADED
        }

        test("a value equal to its new default round-trips even though encodeDefaults omits it from the wire") {
            // `contractJson` does not set `encodeDefaults`, so giving these properties a default makes
            // a default-valued one VANISH from the encoded bytes. That is a real wire change, and it is
            // safe in both directions precisely because the decoder now supplies the same default.
            val issue = ScanIssue("i1", "Author/Book", ScanIssueReason.UNKNOWN, firstSeenAt = 1, lastSeenAt = 2)
            val encoded = contractJson.encodeToString(ScanIssue.serializer(), issue)

            encoded shouldNotContain "reason"
            contractJson.decodeFromString(ScanIssue.serializer(), encoded) shouldBe issue

            val entry = FileEntry("a/b", "b", "", size = 1, mtimeMs = 2, fileType = FileType.UNKNOWN)
            contractJson.decodeFromString(
                FileEntry.serializer(),
                contractJson.encodeToString(FileEntry.serializer(), entry),
            ) shouldBe entry

            val cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "abc123")
            contractJson.decodeFromString(
                CoverPayload.serializer(),
                contractJson.encodeToString(CoverPayload.serializer(), cover),
            ) shouldBe cover
        }

        // Control probe: a tolerance setting must be shown to fail when unset, or the tests above
        // would pass with or without it and prove nothing.
        test("WITHOUT coerceInputValues the same payloads throw — the setting is load-bearing") {
            val strictJson =
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }

            shouldThrow<SerializationException> { strictJson.decodeFromString<ScanIssue>(futureScanIssue) }
            shouldThrow<SerializationException> { strictJson.decodeFromString<FileEntry>(futureFileEntry) }
            shouldThrow<SerializationException> { strictJson.decodeFromString<CollectionShareSyncPayload>(futureShare) }
            shouldThrow<SerializationException> { strictJson.decodeFromString<CoverPayload>(futureCover) }
        }
    })
