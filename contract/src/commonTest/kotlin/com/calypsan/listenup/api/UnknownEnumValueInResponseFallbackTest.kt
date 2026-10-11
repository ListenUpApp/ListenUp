package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.CoverOption
import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.dto.ExternalRatingsCheck
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.MatchProvenance
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.dto.imports.MatchTier as ImportMatchTier
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.dto.invite.InviteStatus
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.api.dto.match.BookCandidate
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.YourCopy
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.SidecarCuration
import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.appJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
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

        val library =
            Library(
                id = LibraryId("l1"),
                name = "Library",
                folders = emptyList(),
                metadataPrecedence = "embedded",
                accessMode = AccessMode.PRIVATE,
                createdByUserId = null,
                createdAt = 1,
            )
        val invite =
            InviteSummary(
                InviteDto(InviteId("i1"), "code", "a@b.c", "A", UserRole.MEMBER, "admin1", expiresAt = 2, createdAt = 1),
                InviteStatus.CLAIMED,
            )
        val import = ImportSummary(ImportId("imp1"), createdAt = 1, status = ImportStatus.MAPPED, bookCount = 2, userCount = 1)
        val uploaded = UploadedBook(title = "Book", status = UploadedBookStatus.DUPLICATE)
        val change = EntityChange(StoryWorldHistoryId("h1"), EntityId("e1"), StoryWorldOp.MERGE, occurredAt = 1)
        val reset = PasswordResetStatusEvent(PasswordResetStatus.APPROVED, expiresAt = 5)
        val progress =
            ScanEvent.Progress(
                correlationId = "c1",
                libraryId = LibraryId("l1"),
                phase = ScanPhase.ANALYZING,
                filesWalked = 1,
                booksAnalyzed = 1,
                errors = 0,
            )
        val broken = HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED)
        val weak = AuthError.WeakPassword(reason = WeakPasswordReason.TOO_SHORT)

        val absUser = AbsUserMatch(AbsUserId("a1"), "alex", null, UserId("u1"), ImportMatchTier.STRONG)
        val analysis =
            ImportAnalysis(
                userMatches = emptyList(),
                bookMatchCounts = mapOf(ImportMatchTier.ASIN to 4, ImportMatchTier.PATH to 2),
                ambiguous = emptyList(),
                unmatched = emptyList(),
            )
        val yourCopy = YourCopy(durationMs = 1, narrators = emptyList(), chapterCount = 3, year = 2020, format = EditionFormat.ABRIDGED)
        val candidate =
            BookCandidate(
                key = BookCandidateKey(listOf(ExternalRef("hardcover", "427578"))),
                title = "Project Hail Mary",
                subtitle = null,
                authors = listOf("Andy Weir"),
                narrators = emptyList(),
                durationMs = null,
                year = null,
                format = EditionFormat.ABRIDGED,
                chapterCount = null,
                coverUrl = null,
                foundIn = emptyList(),
                tier = MatchTier.STRONG,
                score = 0.9,
                isBest = true,
                isCurrentLink = false,
                reasons = emptyList(),
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
                FallbackProbe(
                    field = "Library.accessMode",
                    serializer = Library.serializer(),
                    known = library,
                    knownLiteral = "PRIVATE",
                    read = { it.accessMode },
                    fallback = AccessMode.UNKNOWN,
                    atFallback = library.copy(accessMode = AccessMode.UNKNOWN),
                    key = "accessMode",
                ),
                FallbackProbe(
                    field = "InviteSummary.status",
                    serializer = InviteSummary.serializer(),
                    known = invite,
                    knownLiteral = "CLAIMED",
                    read = { it.status },
                    fallback = InviteStatus.UNKNOWN,
                    atFallback = invite.copy(status = InviteStatus.UNKNOWN),
                    key = "status",
                ),
                FallbackProbe(
                    field = "ImportSummary.status",
                    serializer = ImportSummary.serializer(),
                    known = import,
                    knownLiteral = "MAPPED",
                    read = { it.status },
                    fallback = ImportStatus.UNKNOWN,
                    atFallback = import.copy(status = ImportStatus.UNKNOWN),
                    key = "status",
                ),
                FallbackProbe(
                    field = "UploadedBook.status",
                    serializer = UploadedBook.serializer(),
                    known = uploaded,
                    knownLiteral = "DUPLICATE",
                    read = { it.status },
                    fallback = UploadedBookStatus.UNKNOWN,
                    atFallback = uploaded.copy(status = UploadedBookStatus.UNKNOWN),
                    key = "status",
                ),
                FallbackProbe(
                    field = "EntityChange.op",
                    serializer = EntityChange.serializer(),
                    known = change,
                    knownLiteral = "MERGE",
                    read = { it.op },
                    fallback = StoryWorldOp.UNKNOWN,
                    atFallback = change.copy(op = StoryWorldOp.UNKNOWN),
                    key = "op",
                ),
                FallbackProbe(
                    field = "PasswordResetStatusEvent.status",
                    serializer = PasswordResetStatusEvent.serializer(),
                    known = reset,
                    knownLiteral = "APPROVED",
                    read = { it.status },
                    fallback = PasswordResetStatus.UNKNOWN,
                    atFallback = reset.copy(status = PasswordResetStatus.UNKNOWN),
                    key = "status",
                ),
                FallbackProbe(
                    field = "ScanEvent.Progress.phase",
                    serializer = ScanEvent.Progress.serializer(),
                    known = progress,
                    knownLiteral = "ANALYZING",
                    read = { it.phase },
                    fallback = ScanPhase.UNKNOWN,
                    atFallback = progress.copy(phase = ScanPhase.UNKNOWN),
                    key = "phase",
                ),
                FallbackProbe(
                    field = "HardcoverConnection.Broken.reason",
                    serializer = HardcoverConnection.Broken.serializer(),
                    known = broken,
                    knownLiteral = "REVOKED",
                    read = { it.reason },
                    fallback = HardcoverBrokenReason.UNKNOWN,
                    atFallback = broken.copy(reason = HardcoverBrokenReason.UNKNOWN),
                    key = "reason",
                ),
                FallbackProbe(
                    field = "AuthError.WeakPassword.reason",
                    serializer = AuthError.WeakPassword.serializer(),
                    known = weak,
                    knownLiteral = "TOO_SHORT",
                    read = { it.reason },
                    fallback = WeakPasswordReason.UNKNOWN,
                    atFallback = weak.copy(reason = WeakPasswordReason.UNKNOWN),
                    key = "reason",
                ),
                FallbackProbe(
                    field = "RegistrationPolicy as a bare RPC result",
                    serializer = RegistrationPolicy.serializer(),
                    known = RegistrationPolicy.OPEN,
                    knownLiteral = "OPEN",
                    read = { it },
                    fallback = RegistrationPolicy.CLOSED,
                ),
                FallbackProbe(
                    field = "AbsUserMatch.confidence",
                    serializer = AbsUserMatch.serializer(),
                    known = absUser,
                    knownLiteral = "STRONG",
                    read = { it.confidence },
                    fallback = ImportMatchTier.UNMATCHED,
                ),
                FallbackProbe(
                    field = "ImportAnalysis.bookMatchCounts (the unknown key is dropped, the rest survive)",
                    serializer = ImportAnalysis.serializer(),
                    known = analysis,
                    knownLiteral = "ASIN",
                    read = { it.bookMatchCounts },
                    fallback = mapOf(ImportMatchTier.PATH to 2),
                ),
                FallbackProbe(
                    field = "YourCopy.format",
                    serializer = YourCopy.serializer(),
                    known = yourCopy,
                    knownLiteral = "ABRIDGED",
                    read = { it.format },
                    fallback = null,
                    atFallback = yourCopy.copy(format = null),
                    key = "format",
                ),
                FallbackProbe(
                    field = "BookCandidate.format",
                    serializer = BookCandidate.serializer(),
                    known = candidate,
                    knownLiteral = "ABRIDGED",
                    read = { it.format },
                    fallback = null,
                    atFallback = candidate.copy(format = null),
                    key = "format",
                ),
                FallbackProbe(
                    field = "LibraryCredit.role",
                    serializer = LibraryCredit.serializer(),
                    known = LibraryCredit(ContributorRole.NARRATOR, bookCount = 3),
                    knownLiteral = "NARRATOR",
                    read = { it.role },
                    fallback = ContributorRole.UNKNOWN,
                ),
                FallbackProbe(
                    field = "PersonCandidate.roles (one element of the list; the rest survive)",
                    serializer = ListSerializer(ContributorRole.serializer()),
                    known = listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                    knownLiteral = "NARRATOR",
                    read = { it },
                    fallback = listOf(ContributorRole.AUTHOR, ContributorRole.UNKNOWN),
                ),
                FallbackProbe(
                    field = "ExternalRatingsCheck as a bare stream element",
                    serializer = ExternalRatingsCheck.serializer(),
                    known = ExternalRatingsCheck.CHECKING,
                    knownLiteral = "CHECKING",
                    read = { it },
                    fallback = ExternalRatingsCheck.UNKNOWN,
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
