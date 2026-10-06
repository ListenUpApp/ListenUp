package com.calypsan.listenup.server.matching

import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MatchScorer
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import kotlin.math.abs

/** One hit, and the source that found it. */
internal data class SourcedHit(
    val source: MetadataProviderId,
    val book: FoundBook,
)

/** A candidate in the making: the hits that are one edition (in source order), and cover-only hits that joined it. */
internal data class MergedHits(
    val members: List<SourcedHit>,
    val attached: List<SourcedHit> = emptyList(),
)

/**
 * Merges hits from different sources into candidates, conservatively (spec, *Find*, step 4): two editions
 * shown separately cost a glance; two editions merged would apply the wrong one. Hits are one candidate when
 * they share an ASIN or an ISBN, or when title, primary author and narrators are all equal, the lengths are
 * within two minutes, and neither is marked a different format. One source's two entries are never merged;
 * one entry found twice (a link and a search) is one hit.
 */
internal object MatchMerger {
    private const val SAME_EDITION_TOLERANCE_MS: Long = 2 * 60_000L

    fun merge(hits: List<SourcedHit>): List<MergedHits> {
        val groups = mutableListOf<MutableList<SourcedHit>>()
        hits.forEach { hit ->
            if (absorbedAsTwin(groups, hit)) return@forEach
            val home =
                groups.firstOrNull { group ->
                    group.none { it.source == hit.source } &&
                        (
                            group.any { sharesIdentifier(it.book, hit.book) } ||
                                isSameEdition(group.first().book, hit.book)
                        )
                }
            if (home != null) home += hit else groups += mutableListOf(hit)
        }
        return groups.map { MergedHits(it.toList()) }
    }

    /**
     * Cover-only hits join every candidate whose first hit has their title and primary author, at most one per
     * source per candidate. They never stand as a candidate of their own.
     */
    fun attach(
        groups: List<MergedHits>,
        coverHits: List<SourcedHit>,
    ): List<MergedHits> =
        groups.map { group ->
            val anchor = group.members.first().book
            val joining = coverHits.filter { sameTitleAndAuthor(anchor, it.book) }.distinctBy { it.source }
            group.copy(attached = group.attached + joining)
        }

    /** Folds [hit] into the entry it duplicates — same source, same key — and says whether it found one. */
    private fun absorbedAsTwin(
        groups: List<MutableList<SourcedHit>>,
        hit: SourcedHit,
    ): Boolean {
        for (group in groups) {
            val twin = group.indexOfFirst { it.source == hit.source && it.book.key == hit.book.key }
            if (twin >= 0) {
                group[twin] = group[twin].absorb(hit)
                return true
            }
        }
        return false
    }

    private fun isSameEdition(
        a: FoundBook,
        b: FoundBook,
    ): Boolean {
        val lengthA = a.durationMs ?: return false
        val lengthB = b.durationMs ?: return false
        return sameTitleAndAuthor(a, b) &&
            abs(lengthA - lengthB) <= SAME_EDITION_TOLERANCE_MS &&
            MatchScorer.sameNames(a.narrators, b.narrators) &&
            (a.format == null || b.format == null || a.format == b.format)
    }

    private fun sameTitleAndAuthor(
        a: FoundBook,
        b: FoundBook,
    ): Boolean {
        val authorA = a.authors.firstOrNull() ?: return false
        val authorB = b.authors.firstOrNull() ?: return false
        return MatchScorer.sameText(a.title, b.title) && MatchScorer.sameNames(listOf(authorA), listOf(authorB))
    }

    private fun sharesIdentifier(
        a: FoundBook,
        b: FoundBook,
    ): Boolean {
        val asin = a.asin?.takeIf { it.isNotBlank() }
        if (asin != null && asin.equals(b.asin, ignoreCase = true)) return true
        val isbn = a.isbnDigits() ?: return false
        return isbn == b.isbnDigits()
    }

    private fun FoundBook.isbnDigits(): String? =
        isbn
            ?.filter { it.isDigit() || it == 'X' || it == 'x' }
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }

    /** The same entry found twice stays one hit: it remembers either was the link, and prefers the searched store. */
    private fun SourcedHit.absorb(other: SourcedHit): SourcedHit =
        copy(
            book =
                book.copy(
                    viaLink = book.viaLink || other.book.viaLink,
                    chapterCount = book.chapterCount ?: other.book.chapterCount,
                    region = if (book.viaLink && !other.book.viaLink) other.book.region ?: book.region else book.region,
                ),
        )
}
