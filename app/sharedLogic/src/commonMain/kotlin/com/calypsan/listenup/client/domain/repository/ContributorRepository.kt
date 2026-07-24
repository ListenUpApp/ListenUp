@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for contributor operations.
 *
 * Provides access to contributor information with offline-first patterns.
 * All Flow-returning methods observe the local database for reactive updates.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ContributorRepository {
    /**
     * Observe all contributors reactively, sorted by name.
     *
     * @return Flow emitting list of all contributors
     */
    fun observeAll(): Flow<List<Contributor>>

    /**
     * Observe a specific contributor by ID reactively.
     *
     * @param id The contributor ID
     * @return Flow emitting the contributor or null
     */
    fun observeById(id: String): Flow<Contributor?>

    /**
     * Get a contributor by ID synchronously.
     *
     * @param id The contributor ID
     * @return Contributor if found, null otherwise
     */
    suspend fun getById(id: String): Contributor?

    /**
     * Observe contributors for a specific book reactively.
     *
     * @param bookId The book ID
     * @return Flow emitting list of contributors for the book
     */
    fun observeByBookId(bookId: String): Flow<List<Contributor>>

    /**
     * Get contributors for a specific book synchronously.
     *
     * @param bookId The book ID
     * @return List of contributors for the book
     */
    suspend fun getByBookId(bookId: String): List<Contributor>

    /**
     * Get all book IDs that have a specific contributor.
     *
     * @param contributorId The contributor ID
     * @return List of book IDs with this contributor
     */
    suspend fun getBookIdsForContributor(contributorId: String): List<String>

    /**
     * Observe all book IDs that have a specific contributor reactively.
     *
     * @param contributorId The contributor ID
     * @return Flow emitting list of book IDs with this contributor
     */
    fun observeBookIdsForContributor(contributorId: String): Flow<List<String>>

    // ========== Library View Methods ==========

    /**
     * Observe contributors with book counts, filtered by role.
     *
     * Used for displaying authors, narrators lists in library views.
     *
     * @param role The role to filter by (e.g., "author", "narrator")
     * @return Flow emitting list of contributors with book counts
     */
    fun observeContributorsByRole(role: String): Flow<List<ContributorWithBookCount>>

    // ========== Contributor Detail Methods ==========

    /**
     * Observe all roles a contributor has with book counts per role.
     *
     * Used for contributor detail page to group books by role.
     *
     * @param contributorId The contributor's unique ID
     * @return Flow of role to book count pairs
     */
    fun observeRolesWithCountForContributor(contributorId: String): Flow<List<RoleWithBookCount>>

    /**
     * Observe all books for a specific contributor in a specific role.
     *
     * Used for contributor detail pages to show books grouped by role.
     * Returns books with full contributor information for creditedAs display.
     *
     * @param contributorId The contributor's unique ID
     * @param role The role to filter by (e.g., "author", "narrator")
     * @return Flow emitting list of books with their contributors
     */
    fun observeBooksForContributorRole(
        contributorId: String,
        role: String,
    ): Flow<List<BookWithContributorRole>>

    // ========== Search Methods ==========

    /**
     * Search contributors for autocomplete during book editing.
     *
     * Implements "never stranded" pattern:
     * - Online: uses the server search endpoint (ranked by book count)
     * - Offline: falls back to local Room FTS5 (simpler but always works)
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10)
     * @return Search response with matching contributors
     */
    suspend fun searchContributors(
        query: String,
        limit: Int = 10,
    ): ContributorSearchResponse

    // ========== Mutation Methods ==========

    /**
     * Upsert a contributor.
     *
     * Used for local updates after metadata operations. The contributor
     * will be inserted if it doesn't exist, or updated if it does.
     *
     * @param contributor The contributor to save
     */
    suspend fun upsertContributor(contributor: Contributor)
}

/**
 * Book with contributor role information.
 *
 * Used when displaying books for a contributor in a specific role,
 * where the creditedAs name might differ from the contributor's actual name.
 */
data class BookWithContributorRole(
    val book: BookListItem,
    /** The name credited on this book for this role, if different from contributor's name */
    val creditedAs: String?,
)
