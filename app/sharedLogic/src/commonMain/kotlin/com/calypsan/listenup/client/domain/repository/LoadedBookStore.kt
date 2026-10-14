package com.calypsan.listenup.client.domain.repository

/**
 * Where this device keeps the id of its loaded book (spec §4.4, S2). Device-local, never synced. Read and
 * written only by `LoadedBookRepositoryImpl`.
 */
interface LoadedBookStore {
    /** The persisted id, or null. Reads storage directly, so it works before local preferences are loaded. */
    suspend fun readLoadedBookId(): String?

    /** Persist [bookId]; null removes the key. */
    suspend fun saveLoadedBookId(bookId: String?)

    /**
     * Whether the key has been seeded once from the last-played book (installs from before the key had none).
     * Set once and never cleared by Close book, so a book the listener closed is not seeded back.
     */
    suspend fun readLoadedBookMigrated(): Boolean

    /** Record that the seed has run. */
    suspend fun saveLoadedBookMigrated()
}
