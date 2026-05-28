package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.FacetBucket
import com.calypsan.listenup.api.dto.SearchFacets
import com.calypsan.listenup.api.dto.TypeCounts
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Computes [SearchFacets] over the full matched book set (FTS match + the same filter WHERE
 * fragment as the book search, with NO display limit). One GROUP BY query per dimension; the
 * genre/author/narrator joins mirror the book-search filter so counts agree with the filtered
 * result. Runs inside the caller's transaction.
 */
internal class SearchFacetCounter {
    /**
     * Computes the facets that depend ONLY on the matched-book set: the matched-book `COUNT(*)`
     * (carried as `types.books`) plus the genre/author/narrator GROUP BY buckets. The other
     * [TypeCounts] fields (contributors/series/tags) are left at `0` here — those are display-list
     * sizes the caller fills in after its parallel search queries resolve.
     *
     * @param matchedBooksSql a subquery selecting matched book ids
     *   (`SELECT b.id FROM book_search s … WHERE … <filter>`).
     * @param args positional args for [matchedBooksSql] (fts query + filter args).
     * @param bookCount the true matched-book count (`COUNT(*)` over [matchedBooksSql]).
     */
    fun count(
        matchedBooksSql: String,
        args: List<Pair<IColumnType<*>, Any>>,
        bookCount: Int,
        topN: Int = TOP_N,
    ): SearchFacets =
        SearchFacets(
            types = TypeCounts(books = bookCount, contributors = 0, series = 0, tags = 0),
            genres = genreFacet(matchedBooksSql, args, topN),
            authors = contributorFacet(matchedBooksSql, args, ContributorRole.Author, topN),
            narrators = contributorFacet(matchedBooksSql, args, ContributorRole.Narrator, topN),
        )

    private fun genreFacet(
        matchedBooksSql: String,
        args: List<Pair<IColumnType<*>, Any>>,
        topN: Int,
    ): List<FacetBucket> {
        val out = mutableListOf<FacetBucket>()
        TransactionManager.current().exec(
            stmt =
                "SELECT g.slug AS k, g.name AS label, COUNT(*) AS cnt " +
                    "FROM ($matchedBooksSql) mb " +
                    "JOIN book_genres bg ON bg.book_id = mb.id " +
                    "JOIN genres g ON g.id = bg.genre_id AND g.deleted_at IS NULL " +
                    "GROUP BY g.id ORDER BY cnt DESC, g.name LIMIT $topN",
            args = args,
        ) { rs -> while (rs.next()) out += FacetBucket(rs.getString("k"), rs.getString("label"), rs.getInt("cnt")) }
        return out
    }

    private fun contributorFacet(
        matchedBooksSql: String,
        args: List<Pair<IColumnType<*>, Any>>,
        role: ContributorRole,
        topN: Int,
    ): List<FacetBucket> {
        val out = mutableListOf<FacetBucket>()
        TransactionManager.current().exec(
            stmt =
                "SELECT c.id AS k, c.name AS label, COUNT(*) AS cnt " +
                    "FROM ($matchedBooksSql) mb " +
                    "JOIN book_contributors bc ON bc.book_id = mb.id AND bc.role = '${role.dbValue}' " +
                    "JOIN contributors c ON c.id = bc.contributor_id AND c.deleted_at IS NULL " +
                    "GROUP BY c.id ORDER BY cnt DESC, c.name LIMIT $topN",
            args = args,
        ) { rs -> while (rs.next()) out += FacetBucket(rs.getString("k"), rs.getString("label"), rs.getInt("cnt")) }
        return out
    }

    private enum class ContributorRole(
        val dbValue: String,
    ) {
        Author("author"),
        Narrator("narrator"),
    }

    private companion object {
        const val TOP_N = 20
    }
}
