package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult

/** Which catalog a [MetadataProvider] represents. Only Audible is implemented today. */
enum class MetadataSource { AUDIBLE, }

/**
 * A source of canonical book metadata. Owns search + by-id fetch + chapter fetch for one
 * catalog, returning wire [MetadataBook]/[MetadataChapters] DTOs (no source-internal types
 * cross this boundary). Add a new metadata source by implementing this and registering it in
 * the `List<MetadataProvider>` built in `MetadataModule`.
 */
interface MetadataProvider {
    /** Which catalog this provider represents. */
    val source: MetadataSource

    /**
     * Searches the catalog for books matching [query]. A `null` [region] uses the provider's
     * configured default region with a fallback; an explicit [region] queries it directly.
     */
    suspend fun search(
        query: String,
        region: AudibleRegion?,
    ): AppResult<List<MetadataBook>>

    /**
     * Fetches full metadata for the book identified by [id] (the catalog's external key) in
     * [region]. Returns `null` inside [AppResult.Success] when the catalog has no such id.
     * Pass [refresh] = `true` to bypass any cache.
     */
    suspend fun getBook(
        id: String,
        region: AudibleRegion,
        refresh: Boolean = false,
    ): AppResult<MetadataBook?>

    /**
     * Fetches the chapter list for the book identified by [id] in [region]. Returns `null`
     * inside [AppResult.Success] when no chapter data is available.
     */
    suspend fun getChapters(
        id: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?>
}
