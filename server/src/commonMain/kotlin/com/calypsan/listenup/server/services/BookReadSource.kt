package com.calypsan.listenup.server.services

/**
 * The values of `book_reads.source`: whose read a row is. [PLAYBACK] and [RECONCILE] are ListenUp's
 * own listening; [HARDCOVER] is a read logged on Hardcover and pulled (#601 B3), which is shown
 * (badged) but never counts as listening. The `.sq` queries spell the Hardcover value literally —
 * `source <> 'hardcover'` — so BookReadsQueriesClassifiedTest can see which side each query is on.
 */
object BookReadSource {
    /** A finish ListenUp recorded from playback (`recordCompletion`). */
    const val PLAYBACK: String = "playback"

    /** A finish recovered from a finished position during a stats rebuild. */
    const val RECONCILE: String = "reconcile"

    /** A read pulled from the user's Hardcover shelf. */
    const val HARDCOVER: String = "hardcover"
}
