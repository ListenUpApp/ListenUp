package com.calypsan.listenup.client.data.local.db

/**
 * A book's primary author, as one scalar subquery over the enclosing query's `b` (the `books` row): the first
 * author in credit order, named as credited.
 *
 * One fragment, spliced into every query that shows a single author for a book (Discover, Activity, Listening
 * now), so none of them picks its own: Book Detail lists authors in this same order with this same name
 * ([extractByRole]). `ordinal` is the credit position; `rowid` breaks the tie for rows written before it existed,
 * which were inserted in the server's order. `creditedAs` keeps a pen name ("Richard Bachman") over the linked
 * contributor's own name.
 */
internal const val PRIMARY_AUTHOR_NAME_SQL =
    "(SELECT COALESCE(pa_bc.creditedAs, pa_c.name) FROM book_contributors pa_bc " +
        "INNER JOIN contributors pa_c ON pa_c.id = pa_bc.contributorId " +
        "WHERE pa_bc.bookId = b.id AND pa_bc.role = 'author' " +
        "ORDER BY pa_bc.ordinal, pa_bc.rowid LIMIT 1)"
