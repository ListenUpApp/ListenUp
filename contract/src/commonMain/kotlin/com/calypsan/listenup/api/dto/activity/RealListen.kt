package com.calypsan.listenup.api.dto.activity

/**
 * What counts as really listening — one definition, read by the client's Activity feed and by the
 * server's "started a book" signal, so "worth telling people about" means the same thing everywhere.
 *
 * Below [THRESHOLD_MS], listening is still real for stats and progress — it just isn't news: an
 * eight-second tap on a book is not something the feed should announce.
 */
object RealListen {
    /** Listening shorter than this, in one sitting or one listen-through, is not announced. */
    const val THRESHOLD_MS: Long = 60_000L

    /**
     * Longest idle stretch that still counts as the same sitting. Pausing to make coffee or take a
     * call does not end an evening with a book; picking it up again after lunch is a separate act.
     */
    const val SITTING_GAP_MS: Long = 60L * 60_000L
}
