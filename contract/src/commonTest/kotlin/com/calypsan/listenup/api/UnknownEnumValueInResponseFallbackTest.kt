package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.CoverOption
import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.dto.MatchProvenance
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.SidecarCuration
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.appJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One enum-typed wire field and where an unknown literal must land.
 *
 * [known] is a value whose encoding contains [knownLiteral] in quotes exactly where the enum is
 * written; the probe swaps that literal for one no build has heard of and reads the result with
 * [read]. [atFallback], when given, is the same value holding its fallback: its [key] must still
 * be written, or an app built before the default existed fails on the missing key.
 */
private class FallbackProbe<T>(
    val field: String,
    val serializer: KSerializer<T>,
    val known: T,
    val knownLiteral: String,
    val read: (T) -> Any?,
    val fallback: Any?,
    val atFallback: T? = null,
    val key: String? = null,
) {
    fun unknownLandsOnFallback(json: Json) {
        val encoded = json.encodeToString(serializer, known)
        encoded shouldContain "\"$knownLiteral\""

        read(json.decodeFromString(serializer, encoded.replace("\"$knownLiteral\"", "\"FROM_THE_FUTURE\""))) shouldBe fallback
    }

    fun knownDecodesAsItself(json: Json) {
        json.decodeFromString(serializer, json.encodeToString(serializer, known)) shouldBe known
    }

    fun fallbackIsStillWritten(json: Json) {
        val value = atFallback ?: return
        json.encodeToString(serializer, value) shouldContain "\"$key\":"
    }
}

/**
 * The enum-value axis of the additive-wire guarantee for ordinary responses and streams: the fields
 * [UnknownEnumValueFallbackTest] does not already cover. Every row is a field the Konsist rule
 * `EnumWireFieldHasFallbackOrIsStrictRule` accepts as having a fallback; this proves the fallback
 * fires, that a known literal is not swallowed by it, and that the fallback value is still encoded.
 */
class UnknownEnumValueInResponseFallbackTest :
    FunSpec({
        val title = BookField.TITLE
        val scanned = FieldProvenance(FieldSourceKind.USER)
        val collection =
            CollectionSummary(
                id = CollectionId("c1"),
                name = "Favourites",
                ownerId = UserId("u1"),
                isInbox = false,
                bookCount = 3,
                callerPermission = SharePermission.Write,
                isOwner = false,
            )
        val share = CollectionShareDto("s1", CollectionId("c1"), UserId("u2"), SharePermission.Write)
        val cover = CoverOption(CoverOptionSource.ITUNES, "https://example.test/c.jpg", 600, 600, "42")
        val analyzed =
            AnalyzedBook(
                candidate = CandidateBook("Author/Book", false, emptyList()),
                title = "Book",
                fieldProvenance = mapOf(title to scanned, BookField.SUBTITLE to scanned),
            )

        val probes =
            listOf(
                FallbackProbe(
                    field = "CollectionSummary.callerPermission",
                    serializer = CollectionSummary.serializer(),
                    known = collection,
                    knownLiteral = "write",
                    read = { it.callerPermission },
                    fallback = SharePermission.Read,
                    atFallback = collection.copy(callerPermission = SharePermission.Read),
                    key = "callerPermission",
                ),
                FallbackProbe(
                    field = "CollectionShareDto.permission",
                    serializer = CollectionShareDto.serializer(),
                    known = share,
                    knownLiteral = "write",
                    read = { it.permission },
                    fallback = SharePermission.Read,
                    atFallback = share.copy(permission = SharePermission.Read),
                    key = "permission",
                ),
                FallbackProbe(
                    field = "CoverOption.source",
                    serializer = CoverOption.serializer(),
                    known = cover,
                    knownLiteral = "ITUNES",
                    read = { it.source },
                    fallback = CoverOptionSource.OTHER,
                    atFallback = cover.copy(source = CoverOptionSource.OTHER),
                    key = "source",
                ),
                FallbackProbe(
                    field = "MatchProvenance.fallbackFields (the unknown key is dropped, the rest survive)",
                    serializer = MatchProvenance.serializer(),
                    known = MatchProvenance(fallbackFields = mapOf(title to "iTunes", BookField.SUBTITLE to "Audnexus")),
                    knownLiteral = title.name,
                    read = { it.fallbackFields },
                    fallback = mapOf(BookField.SUBTITLE to "Audnexus"),
                ),
                FallbackProbe(
                    field = "AnalyzedBook.fieldProvenance (the unknown key is dropped, the rest survive)",
                    serializer = AnalyzedBook.serializer(),
                    known = analyzed,
                    knownLiteral = title.name,
                    read = { it.fieldProvenance },
                    fallback = mapOf(BookField.SUBTITLE to scanned),
                ),
                FallbackProbe(
                    field = "SidecarCuration.fieldProvenance (the unknown key is dropped, the rest survive)",
                    serializer = SidecarCuration.serializer(),
                    known = SidecarCuration(fieldProvenance = mapOf(title to scanned, BookField.SUBTITLE to scanned)),
                    knownLiteral = title.name,
                    read = { it.fieldProvenance },
                    fallback = mapOf(BookField.SUBTITLE to scanned),
                ),
            )

        // `contractJson` and `appJson` are configured separately, so each is exercised.
        listOf("contractJson" to contractJson, "appJson" to appJson).forEach { (jsonName, json) ->
            probes.forEach { probe ->
                test("$jsonName: an unknown ${probe.field} lands on its fallback") { probe.unknownLandsOnFallback(json) }
                test("$jsonName: a known ${probe.field} still decodes as itself") { probe.knownDecodesAsItself(json) }
                test("$jsonName: ${probe.field} is written even when it holds the fallback") { probe.fallbackIsStillWritten(json) }
            }
        }
    })
