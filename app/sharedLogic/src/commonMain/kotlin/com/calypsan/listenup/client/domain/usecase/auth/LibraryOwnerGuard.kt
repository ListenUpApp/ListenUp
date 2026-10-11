package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.core.SecureStorage

/**
 * Keeps the local library copy to one person.
 *
 * The copy in Room — positions, shelves, history, sync cursors — belongs to whoever was signed in
 * when it was pulled. A lapsed session deliberately keeps it so that person can go on listening
 * offline and sign back in. This guard runs before any OTHER sign-in is persisted and wipes the copy
 * first when the incoming user is not its owner. The user-side twin of [AdoptServerUseCase], which
 * makes the same decision about the server.
 */
internal class LibraryOwnerGuard(
    private val secureStorage: SecureStorage,
    private val persistedUserId: suspend () -> String?,
    private val storedUserId: suspend () -> String?,
    private val localSignOut: suspend () -> Unit,
    private val deleteDeviceFiles: suspend () -> Unit,
) {
    /** Call before the incoming session's user or tokens are written anywhere. */
    suspend fun beforeSignIn(incomingUserId: String) {
        // The recorded owner decides. An install from before it was recorded has only the lapsed
        // session's user id, or failing that the user row still in the database, to say whose copy this is.
        val owner = secureStorage.read(KEY_LIBRARY_OWNER_USER_ID) ?: persistedUserId() ?: storedUserId()
        if (owner != null && owner != incomingUserId) {
            localSignOut()
            deleteDeviceFiles()
        }
        // Recorded only after the wipe succeeded: a failed wipe must be retried, not skipped next time.
        secureStorage.save(KEY_LIBRARY_OWNER_USER_ID, incomingUserId)
    }

    private companion object {
        const val KEY_LIBRARY_OWNER_USER_ID = "library_owner_user_id"
    }
}
