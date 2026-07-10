package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Thin SQLDelight wrapper over `sidecar_write_state` — the round-trip discriminator
 * table [SidecarWriter] records into after every successful `listenup.json` write, and
 * [com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader] consults on read
 * to tell a self-written file (skip re-ingestion) from an externally-edited one (ingest).
 */
class SidecarWriteStateRepository(
    private val db: ListenUpDatabase,
) {
    /** Records (or replaces) [bookId]'s last-written content hash and timestamp. */
    suspend fun save(
        bookId: String,
        contentHashHex: String,
        writtenAtMs: Long,
    ) {
        suspendTransaction(db) {
            db.sidecarWriteStateQueries.upsert(
                book_id = bookId,
                content_hash = contentHashHex,
                written_at = writtenAtMs,
            )
        }
    }

    /** The recorded write state for [bookId], or `null` if none was ever written. */
    suspend fun findByBookId(bookId: String): SidecarWriteStateRow? =
        suspendTransaction(db) {
            db.sidecarWriteStateQueries
                .selectByBookId(bookId)
                .executeAsOneOrNull()
                ?.let { row ->
                    SidecarWriteStateRow(
                        bookId = row.book_id,
                        contentHashHex = row.content_hash,
                        writtenAtMs = row.written_at,
                    )
                }
        }

    /** Clears [bookId]'s recorded write state. */
    suspend fun deleteForBook(bookId: String) {
        suspendTransaction(db) {
            db.sidecarWriteStateQueries.deleteForBook(bookId)
        }
    }
}

/** One recorded `listenup.json` write for a book: the content hash and when it landed. */
data class SidecarWriteStateRow(
    val bookId: String,
    val contentHashHex: String,
    val writtenAtMs: Long,
)
