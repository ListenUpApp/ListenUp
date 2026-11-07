package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditForm
import com.calypsan.listenup.client.presentation.edit.FormSlot
import com.calypsan.listenup.client.presentation.bookedit.EditableCollection
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private val logger = KotlinLogging.logger {}

private const val SEARCH_LIMIT = 10

/**
 * Delegate handling collection editing operations (admin-only).
 *
 * Responsibilities:
 * - Reactively loads the book's current collection memberships and the available
 *   collection list (both from the local Room mirror via [CollectionRepository]).
 * - Local filtering of available collections (no create-new — collections are
 *   admin-managed on the collection screens, picked here).
 * - Add/remove a collection from the book's pending set.
 *
 * Mirrors [GenreTagEditDelegate]: `state.update { }` + [onChangesMade] on every
 * mutation, local filtering for the picker. No optimistic Room writes — the save
 * path dispatches `setBookCollections` and the firehose echo delivers the new state.
 *
 * @property state Shared state flow owned by ViewModel.
 * @property collectionRepository Source of the current + available collections.
 * @property scope CoroutineScope for the reactive load.
 * @property onChangesMade Callback to notify ViewModel of changes.
 */
internal class CollectionEditDelegate(
    private val state: FormSlot<*, BookEditForm>,
    private val collectionRepository: CollectionRepository,
    private val scope: CoroutineScope,
    private val onChangesMade: () -> Unit,
) {
    /**
     * The form being edited. Outside `Editing` this is an empty form: the reads it feeds only shape
     * a write, and [FormSlot.update] drops every write while there is no form.
     */
    private val current: BookEditForm get() = state.value ?: BookEditForm()

    /**
     * The book's non-system collection ids at load time — the baseline the dirty-check
     * diffs against. `null` until the first reactive load emission lands.
     */
    private var originalCollectionIds: Set<String>? = null

    /**
     * Start observing the current memberships of [bookId] and the available list.
     *
     * The current set seeds both [BookEditForm.collections] (the pending edit set)
     * and [originalCollectionIds] (the dirty-check baseline), captured once on the
     * first emission so subsequent server echoes don't clobber unsaved edits.
     */
    fun loadCollections(bookId: String) {
        combine(
            collectionRepository.observeCollections(),
            collectionRepository.observeBookCollectionIds(bookId),
        ) { all, currentIds ->
            all to currentIds
        }.onEach { (all, currentIds) ->
            val available = all.filterNot { it.isSystem }.map { EditableCollection(id = it.id, name = it.name) }
            val current = available.filter { it.id in currentIds }

            if (originalCollectionIds == null) {
                // The baseline is the same set the user edits: system memberships (ALL_BOOKS,
                // INBOX) are server-managed and never in the pending set, so counting them here
                // would make every public or held book read as edited.
                originalCollectionIds = current.map { it.id }.toSet()
                state.update { it.copy(allCollections = available, collections = current) }
            } else {
                // Baseline already captured — refresh the available list only; never
                // clobber the user's pending edits.
                state.update { it.copy(allCollections = available) }
            }
        }.launchIn(scope)
    }

    /**
     * Update the collection search query and filter results locally.
     */
    fun updateCollectionSearchQuery(query: String) {
        state.update { it.copy(collectionSearchQuery = query) }
        filterCollections(query)
    }

    /**
     * Add a collection to the book's pending set.
     */
    fun selectCollection(collection: EditableCollection) {
        state.update { current ->
            if (current.collections.any { it.id == collection.id }) {
                return@update current.copy(
                    collectionSearchQuery = "",
                    collectionSearchResults = emptyList(),
                )
            }

            current.copy(
                collections = current.collections + collection,
                collectionSearchQuery = "",
                collectionSearchResults = emptyList(),
            )
        }
        onChangesMade()
    }

    /**
     * Remove a collection from the book's pending set.
     */
    fun removeCollection(collection: EditableCollection) {
        state.update { current ->
            current.copy(collections = current.collections.filter { it.id != collection.id })
        }
        onChangesMade()
    }

    /**
     * Whether the pending collection set differs from the load-time baseline.
     *
     * Order-insensitive — collection membership is a set, not a list.
     */
    fun hasChanges(): Boolean {
        val baseline = originalCollectionIds ?: return false
        return current.collections
            .map { it.id }
            .toSet() != baseline
    }

    private fun filterCollections(query: String) {
        if (query.isBlank()) {
            state.update { it.copy(collectionSearchResults = emptyList()) }
            return
        }

        val lowerQuery = query.lowercase()
        val currentIds =
            current.collections
                .map { it.id }
                .toSet()

        val filtered =
            current.allCollections
                .filter { it.id !in currentIds && it.name.lowercase().contains(lowerQuery) }
                .take(SEARCH_LIMIT)

        state.update { it.copy(collectionSearchResults = filtered) }
        logger.debug { "Collection search: ${filtered.size} results" }
    }
}
