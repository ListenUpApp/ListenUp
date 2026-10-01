package com.calypsan.listenup.client.data.local.db

/**
 * The ids of every book held for review: a live `collection_books` membership in a live INBOX
 * collection.
 *
 * One fragment, spliced into every query that has to agree about what "held" means — the held-set
 * read ([CollectionBookDao.observeHeldBookIds]), the `NOT IN` that keeps held books out of the
 * library's lists, and the `IN` that marks them in search. Owning the definition once is what stops
 * the badge, the library and the inbox from disagreeing.
 *
 * Only admins ever receive INBOX rows, so on a member's device this selects nothing and every query
 * that uses it behaves exactly as it did before — no role check is needed in the data layer.
 *
 * The aliases are prefixed `held_` so the fragment can sit inside a query that already uses `c` or
 * `cb` without shadowing them. It is uncorrelated, so SQLite evaluates it once per query.
 */
internal const val HELD_BOOK_IDS_SQL =
    "SELECT held_cb.bookId FROM collection_books held_cb " +
        "INNER JOIN collections held_c ON held_c.id = held_cb.collectionId " +
        "WHERE held_c.isInbox = 1 AND held_c.deletedAt IS NULL AND held_cb.deletedAt IS NULL"
