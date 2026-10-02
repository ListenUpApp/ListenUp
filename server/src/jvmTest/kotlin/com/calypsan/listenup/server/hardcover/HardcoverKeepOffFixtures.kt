package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/** [userId] keeps [bookId] off Hardcover since [at] (#1541), written as the switch writes it. */
internal fun ListenUpDatabase.seedExclusion(
    userId: String,
    bookId: String,
    at: Long,
) = transaction { hardcoverBookExclusionsQueries.insertExclusion(user_id = userId, book_id = bookId, excluded_at = at) }
