package com.calypsan.listenup.client.download

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The book runs this process owns, and the one rule that keeps scheduling and finishing from racing.
 *
 * A run registers when its host starts it and unregisters when it ends. Its decision to finish ("nothing left to
 * transfer") is made under the same lock that [scheduleUnlessRunning] holds, so a file queued while a run is
 * finishing is never lost: either the schedule sees the run and leaves it to pick the file up (the run re-reads the
 * rows before it exits), or the run has already gone and the schedule starts a new one.
 *
 * Books download one at a time: a run waits for its [withTurn] before it transfers anything, and turns are handed out
 * first come, first served, so the queue moves in the order books were started.
 *
 * It knows which files this process is transferring ([claim]), so a file left DOWNLOADING by a process that died can be
 * told from one a run here is moving ([withOwnedFiles]).
 *
 * It also carries what the app meant by a stop it caused ([expectStop]): the platform reports a pause, a cancel and a
 * preference change all as "cancelled by the app", and only the app knows which it was.
 */
internal class BookTransferRegistry {
    /** One registered run. Compared by identity, so a finishing run never removes a newer run of the same book. */
    @Suppress("UseDataClass") // Identity is the point: two runs of one book must never compare equal.
    class Token internal constructor(
        val bookId: String,
    ) {
        internal var expectedStop: DownloadStopReason? = null
    }

    private val lock = Mutex()
    private val turn = Mutex()
    private val running = mutableMapOf<String, Token>()

    /** The files runs in this process have claimed and are transferring. */
    private val owned = mutableSetOf<String>()

    /** Register a run of [bookId]. A newer run replaces an older one's registration. */
    suspend fun register(bookId: String): Token = lock.withLock { Token(bookId).also { running[bookId] = it } }

    /** Unregister [token] if it is still the book's run. Idempotent. */
    suspend fun unregister(token: Token) {
        lock.withLock { if (running[token.bookId] === token) running.remove(token.bookId) }
    }

    /** Whether a run of [bookId] is registered: running, or waiting for its turn. */
    suspend fun isRunning(bookId: String): Boolean = lock.withLock { bookId in running }

    /**
     * Run [schedule] for [bookId] unless a run of it is registered, in which case the run will pick up whatever was
     * queued. Returns [schedule]'s result, or null when it was not needed.
     */
    suspend fun <T> scheduleUnlessRunning(
        bookId: String,
        schedule: suspend () -> T,
    ): T? = lock.withLock { if (bookId in running) null else schedule() }

    /**
     * The next item of [token]'s book from [next], or null after unregistering the run because there is none: the
     * check and the exit are one step under the lock [scheduleUnlessRunning] takes.
     */
    suspend fun <T : Any> nextOrFinish(
        token: Token,
        next: suspend () -> T?,
    ): T? =
        lock.withLock {
            next().also { if (it == null && running[token.bookId] === token) running.remove(token.bookId) }
        }

    /**
     * Claim [audioFileId] through [claim] (the database's guarded claim) and, when it succeeds, record that this
     * process is transferring it, both under the lock [withOwnedFiles] takes: a file is never seen claimed but unowned.
     */
    suspend fun claim(
        audioFileId: String,
        claim: suspend () -> Boolean,
    ): Boolean = lock.withLock { claim().also { claimed -> if (claimed) owned += audioFileId } }

    /** This process is no longer transferring [audioFileId]: its run recorded how the file ended. */
    suspend fun release(audioFileId: String) {
        lock.withLock { owned -= audioFileId }
    }

    /** Run [block] with the files this process is transferring, under the lock a claim takes. */
    suspend fun <T> withOwnedFiles(block: suspend (Set<String>) -> T): T = lock.withLock { block(owned.toSet()) }

    /** Run [block] once it is this run's turn: one book transfers at a time, in the order runs asked. */
    suspend fun <T> withTurn(block: suspend () -> T): T = turn.withLock { block() }

    /**
     * Note that the app is about to stop [bookId]'s registered run for [reason], so its stop is recorded as that. A
     * no-op when no run is registered: there is nothing to stop.
     */
    suspend fun expectStop(
        bookId: String,
        reason: DownloadStopReason,
    ) {
        lock.withLock { running[bookId]?.expectedStop = reason }
    }

    /** The reason the app gave for stopping [token]'s run; null when the app did not stop it. */
    suspend fun expectedStopOf(token: Token): DownloadStopReason? = lock.withLock { token.expectedStop }
}
