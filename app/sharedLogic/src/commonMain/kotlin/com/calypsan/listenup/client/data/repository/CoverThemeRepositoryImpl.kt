package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.color.CoverSwatches
import com.calypsan.listenup.client.core.color.coverThemesOf
import com.calypsan.listenup.client.core.color.extractSwatches
import com.calypsan.listenup.client.data.covertheme.CoverPixelReader
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.core.BookId
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * [CoverThemeRepository] over a platform [CoverPixelReader]. Caches each cover's swatches (the expensive part) in an
 * LRU keyed by cover hash (spec R4: memory only); a theme is re-derived per style on every read, which is cheap.
 * The map is immutable and swapped atomically, so [cached] is safe from the main thread while a decode finishes. It is
 * an atomic reference, not a `StateFlow`: a reordered map equals the original, and a `StateFlow` would conflate the
 * recency move away.
 */
internal class CoverThemeRepositoryImpl(
    private val reader: CoverPixelReader,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val capacity: Int = SWATCH_CACHE_CAPACITY,
) : CoverThemeRepository {
    private val swatchesByHash = atomic<Map<String, CoverSwatches>>(emptyMap())

    override fun cached(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): CoverThemes? = coverHash?.let { swatchesByHash.value[it] }?.let { coverThemesOf(it, style) }

    override fun observe(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): Flow<CoverThemes?> =
        flow {
            val known = coverHash?.let(::recall)
            val swatches = known ?: decode(bookId, coverHash)?.also { found -> coverHash?.let { keep(it, found) } }
            emit(swatches?.let { coverThemesOf(it, style) })
        }

    private suspend fun decode(
        bookId: BookId,
        coverHash: String?,
    ): CoverSwatches? =
        withContext(decodeDispatcher) {
            reader.read(bookId, coverHash, SAMPLE_EDGE)?.let { extractSwatches(it.argb, it.width, it.height) }
        }

    /** A hit moves to the newest end, so the LRU evicts what nobody has opened for longest. */
    private fun recall(hash: String): CoverSwatches? =
        swatchesByHash.value[hash]?.also { found -> swatchesByHash.update { (it - hash) + (hash to found) } }

    private fun keep(
        hash: String,
        found: CoverSwatches,
    ) = swatchesByHash.update { current ->
        val next = (current - hash) + (hash to found)
        if (next.size > capacity) next - next.keys.first() else next
    }
}

/** Spec §2.3: "≈ 200 entries". */
private const val SWATCH_CACHE_CAPACITY = 200

/** Spec §2.3 step 1: "≤ 64×64". */
private const val SAMPLE_EDGE = 64
