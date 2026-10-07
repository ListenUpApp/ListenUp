package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import kotlinx.rpc.annotations.Rpc

/**
 * Match details: find a book or a person in every catalogue, review per field, apply once, undo.
 *
 * Supersedes [MetadataLookupService] for new clients; that service stays, unchanged, for older ones. Find,
 * Review and Apply are stateless on the server — a candidate is identified by its
 * [com.calypsan.listenup.api.dto.match.BookCandidateKey].
 */
@Rpc
interface MatchingService {
    /**
     * Finds [bookId] in every capable catalogue and ranks what it finds against the book itself. The caller
     * must be able to edit metadata and see the book; otherwise this is `MetadataError.NotFound`.
     *
     * Every source failing is still a success: [BookFindResult.sources] says how each fared, and the client
     * picks the failure screen. Retry is this same call — sources that already answered come back from the
     * server's cache.
     */
    suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult>

    /**
     * Finds [contributorId] in every catalogue with profiles for [PersonFindRequest.role] — authors at Audible and
     * Hardcover, narrators at Hardcover — starting from their own refs, then the books they're credited on in this
     * library, then their name, and ranks what it finds by those books. Gated like [findBookMatches]: the caller
     * needs `canEdit`; an unknown contributor is `MetadataError.NotFound`. Every source failing, or none having
     * profiles for the role, is still a success: [PersonFindResult.sources] and [PersonFindResult.coverage] say why.
     */
    suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult>

    /**
     * Reviews [candidate] (a Find candidate's key) against [bookId]: every field Yours → Proposed with its
     * sources, the cover, genres, moods and chapter names. [region] is the store to read when the candidate's
     * own refs don't carry one. Gated like Find. A source that didn't answer in time is
     * `MetadataError.ExternalTimeout`; every source failing is `MetadataError.ExternalUnavailable`.
     */
    suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<BookMatchReview>

    /**
     * Applies [request] to [bookId] in one transaction — fields, cover, genres, moods, chapter names and refs —
     * and returns the receipt. Nothing is written on any failure: `MetadataError.ReviewOutdated` when the book
     * or a chosen option changed since the Review, `MetadataError.CoverDownloadFailed`, or
     * `MetadataError.ChapterCountMismatch`.
     */
    suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<Mutated<MatchReceipt>>

    /**
     * Reviews [candidate] (a people Find candidate's key) against [contributorId] for [role]: the photo and the
     * biography, Yours → Proposed per source. Never the name. Gated like [findPeople]. A source that didn't answer
     * in time is `MetadataError.ExternalTimeout`; every source failing is `MetadataError.ExternalUnavailable`; no
     * source having a photo or a biography is `MetadataError.NotFound`.
     */
    suspend fun reviewPersonMatch(
        contributorId: ContributorId,
        candidate: PersonCandidateKey,
        role: ContributorRole,
    ): AppResult<PersonMatchReview>

    /**
     * Applies [request] to [contributorId] in one transaction — the photo and the biography as chosen, the
     * candidate's refs — and returns the receipt. Never renames. Nothing is written on any failure:
     * `MetadataError.ReviewOutdated` when the person or a chosen option changed since the Review,
     * `MetadataError.CoverDownloadFailed` when the chosen photo couldn't be fetched.
     */
    suspend fun applyPersonMatch(
        contributorId: ContributorId,
        request: PersonMatchApply,
    ): AppResult<Mutated<MatchReceipt>>

    /**
     * Undoes the match [receiptId], restoring everything it changed. Any editor of the matched entity may undo.
     * `MetadataError.UndoExpired` when it was already undone or the entity has changed since.
     */
    suspend fun undoMatch(receiptId: String): AppResult<Mutated<UndoResult>>
}
