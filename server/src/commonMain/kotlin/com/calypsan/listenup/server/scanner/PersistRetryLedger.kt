package com.calypsan.listenup.server.scanner

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Books the persister could not write, which the next scan must offer again.
 *
 * The scanner records a scan as "what the library looks like now" before the persister writes it,
 * and on the next scan an unchanged book is a fingerprint-cache hit with no diff — so a book whose
 * write failed would never be written until its files changed. The persister records each such book
 * here and forgets it once a write (or its removal) lands; the scanner leaves recorded books out of
 * its previous snapshot, so they are analyzed afresh and reported as added.
 *
 * Keyed by the book's folder root path and its root-relative path, the pair that identifies a book
 * across folders. In memory only: a restart clears the scanner's previous snapshot too, so the first
 * scan after boot offers every book anyway. It can never hold more entries than there are books.
 */
internal class PersistRetryLedger {
    private val lock = SynchronizedObject()
    private val pending = mutableSetOf<Pair<String, String>>()

    /** Records that the books at [rootRelPaths] under [folderRootPath] were not written. */
    fun markUnwritten(
        folderRootPath: String,
        rootRelPaths: Collection<String>,
    ) {
        if (rootRelPaths.isEmpty()) return
        synchronized(lock) { rootRelPaths.forEach { pending += folderRootPath to it } }
    }

    /** Forgets the books at [rootRelPaths] under [folderRootPath]: written, or removed, since. */
    fun markSettled(
        folderRootPath: String,
        rootRelPaths: Collection<String>,
    ) {
        if (rootRelPaths.isEmpty()) return
        synchronized(lock) { rootRelPaths.forEach { pending -= folderRootPath to it } }
    }

    /** A snapshot of the books still owed a write, as (folder root path, root-relative path). */
    fun unwritten(): Set<Pair<String, String>> = synchronized(lock) { pending.toSet() }
}
