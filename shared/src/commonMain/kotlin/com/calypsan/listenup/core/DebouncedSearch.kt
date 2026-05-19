@file:OptIn(FlowPreview::class)

package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Reusable debounced search utility.
 *
 * Encapsulates the common pattern of:
 * - MutableStateFlow for query input
 * - Debouncing to avoid excessive API calls
 * - Minimum query length filtering
 * - Job management to cancel in-flight searches
 * - Loading state tracking
 *
 * Single-channel usage (search bar):
 * ```kotlin
 * class SearchViewModel(private val repository: SearchRepository) : ViewModel() {
 *     private val search = DebouncedSearch<Unit, List<Book>>(
 *         scope = viewModelScope,
 *         onSearch = { _, query -> repository.search(query) },
 *         onResult = { _, results -> state.update { it.copy(results = results, isLoading = false) } },
 *         onClear = { _ -> state.update { it.copy(results = emptyList()) } },
 *         onLoadingChange = { _, loading -> state.update { it.copy(isLoading = loading) } }
 *     )
 *
 *     fun onQueryChanged(query: String) = search.setQuery(Unit, query)
 * }
 * ```
 *
 * Multi-channel usage (per-role contributor search):
 * ```kotlin
 * private val search = DebouncedSearch<ContributorRole, List<Contributor>>(
 *     scope = scope,
 *     onSearch = { role, query -> repository.search(query) },
 *     onResult = { role, results -> state.update { it.copy(roleResults = it.roleResults + (role to results)) } },
 *     onClear = { role -> state.update { it.copy(roleResults = it.roleResults - role) } },
 *     onLoadingChange = { role, loading -> state.update { it.copy(roleLoading = it.roleLoading + (role to loading)) } }
 * )
 *
 * fun onQueryChanged(role: ContributorRole, query: String) = search.setQuery(role, query)
 * ```
 *
 * @param K Key type for multi-channel search (use Unit for single channel)
 * @param R Result type returned by search
 * @param scope CoroutineScope for launching search operations
 * @param debounceMs Debounce delay in milliseconds (default 300ms)
 * @param minQueryLength Minimum query length to trigger search (default 2)
 * @param onSearch Suspend function to perform the search
 * @param onResult Callback when results are received
 * @param onClear Callback when query is cleared
 * @param onLoadingChange Optional callback for loading state changes
 * @param onError Optional callback for search errors
 */
class DebouncedSearch<K, R>(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val minQueryLength: Int = DEFAULT_MIN_QUERY_LENGTH,
    private val onSearch: suspend (key: K, query: String) -> R,
    private val onResult: (key: K, result: R) -> Unit,
    private val onClear: (key: K) -> Unit,
    private val onLoadingChange: ((key: K, isLoading: Boolean) -> Unit)? = null,
    private val onError: ((key: K, error: Exception) -> Unit)? = null,
) {
    private val queryFlows = mutableMapOf<K, MutableStateFlow<String>>()
    private val searchJobs = mutableMapOf<K, Job>()
    private val setupKeys = mutableSetOf<K>()

    companion object {
        /** Default debounce delay in milliseconds. */
        const val DEFAULT_DEBOUNCE_MS = 300L

        /** Default minimum query length to trigger search. */
        const val DEFAULT_MIN_QUERY_LENGTH = 2
    }

    /**
     * Get the current query for a key.
     *
     * @param key The search key
     * @return The current query string, or empty if not set
     */
    fun getQuery(key: K): String = queryFlows[key]?.value ?: ""

    /**
     * Observe the query for a key as a StateFlow.
     *
     * @param key The search key
     * @return StateFlow of query string (creates if needed)
     */
    fun observeQuery(key: K): StateFlow<String> = getOrCreateQueryFlow(key)

    /**
     * Set the search query for a key.
     *
     * This automatically sets up the debounced search pipeline if not already done.
     *
     * @param key The search key (use Unit for single-channel search)
     * @param query The search query
     */
    fun setQuery(
        key: K,
        query: String,
    ) {
        ensureSetup(key)
        queryFlows[key]?.value = query
    }

    /**
     * Clear the search for a key.
     *
     * @param key The search key
     */
    fun clear(key: K) {
        searchJobs[key]?.cancel()
        queryFlows[key]?.value = ""
        onClear(key)
    }

    /**
     * Completely remove a key's search state.
     *
     * Use when the key is no longer valid (e.g., role section removed).
     *
     * @param key The search key to remove
     */
    fun remove(key: K) {
        searchJobs[key]?.cancel()
        searchJobs.remove(key)
        queryFlows.remove(key)
        setupKeys.remove(key)
    }

    /**
     * Cancel any in-flight search for a key.
     *
     * @param key The search key
     */
    fun cancel(key: K) {
        searchJobs[key]?.cancel()
    }

    /**
     * Cancel all in-flight searches and clean up.
     */
    fun cancelAll() {
        searchJobs.values.forEach { it.cancel() }
        searchJobs.clear()
    }

    private fun getOrCreateQueryFlow(key: K): MutableStateFlow<String> =
        queryFlows.getOrPut(key) { MutableStateFlow("") }

    private fun ensureSetup(key: K) {
        if (key in setupKeys) return
        setupKeys.add(key)

        val queryFlow = getOrCreateQueryFlow(key)

        queryFlow
            .debounce(debounceMs)
            .distinctUntilChanged()
            .filter { it.length >= minQueryLength || it.isEmpty() }
            .onEach { query ->
                if (query.isBlank()) {
                    onClear(key)
                } else {
                    performSearch(key, query)
                }
            }.launchIn(scope)
    }

    private fun performSearch(
        key: K,
        query: String,
    ) {
        searchJobs[key]?.cancel()
        searchJobs[key] =
            scope.launch {
                onLoadingChange?.invoke(key, true)
                try {
                    val result = onSearch(key, query)
                    onResult(key, result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Preserve cancellation
                } catch (e: Exception) {
                    onError?.invoke(key, e)
                } finally {
                    onLoadingChange?.invoke(key, false)
                }
            }
    }
}

/**
 * Simplified single-channel debounced search.
 *
 * Use when you only have one search input (not keyed by role/category/etc).
 *
 * ```kotlin
 * private val search = SingleDebouncedSearch(
 *     scope = viewModelScope,
 *     onSearch = { query -> repository.search(query) },
 *     onResult = { results -> state.update { it.copy(results = results) } },
 *     onClear = { state.update { it.copy(results = emptyList()) } }
 * )
 *
 * fun onQueryChanged(query: String) = search.setQuery(query)
 * ```
 */
class SingleDebouncedSearch<R>(
    scope: CoroutineScope,
    debounceMs: Long = DebouncedSearch.DEFAULT_DEBOUNCE_MS,
    minQueryLength: Int = DebouncedSearch.DEFAULT_MIN_QUERY_LENGTH,
    onSearch: suspend (query: String) -> R,
    onResult: (result: R) -> Unit,
    onClear: () -> Unit,
    onLoadingChange: ((isLoading: Boolean) -> Unit)? = null,
    onError: ((error: Exception) -> Unit)? = null,
) {
    private val delegate =
        DebouncedSearch<Unit, R>(
            scope = scope,
            debounceMs = debounceMs,
            minQueryLength = minQueryLength,
            onSearch = { _, query -> onSearch(query) },
            onResult = { _, result -> onResult(result) },
            onClear = { onClear() },
            onLoadingChange = onLoadingChange?.let { callback -> { _, loading -> callback(loading) } },
            onError = onError?.let { callback -> { _, error -> callback(error) } },
        )

    /** Current query string. */
    val query: String get() = delegate.getQuery(Unit)

    /** Observable query flow. */
    val queryFlow: StateFlow<String> get() = delegate.observeQuery(Unit)

    /** Set the search query. */
    fun setQuery(query: String) = delegate.setQuery(Unit, query)

    /** Clear the search. */
    fun clear() = delegate.clear(Unit)

    /** Cancel any in-flight search. */
    fun cancel() = delegate.cancel(Unit)
}
