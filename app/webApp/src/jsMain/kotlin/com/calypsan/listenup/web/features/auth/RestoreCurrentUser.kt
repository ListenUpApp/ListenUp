package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps the signed-in reader's own profile in the browser's store — Android's shell does the same on entry. A browser
 * whose database is rebuilt on a visit restores the session but not the user row, and is signed in with no user: Home has no name and no shelves, and the admin entry is gone. The
 * profile is fetched whenever it is missing while signed in and the server can be asked — at start, and again each
 * time the server comes back.
 */
internal class RestoreCurrentUser(
    private val userRepository: UserRepository,
) {
    /** Runs until cancelled: [isSignedIn] and [reachability] say when a fetch is worth trying. */
    suspend fun keepRestored(
        isSignedIn: Flow<Boolean>,
        reachability: Flow<Reachability>,
    ) {
        combine(isSignedIn, reachability) { signedIn, reach -> signedIn to reach }
            .distinctUntilChanged()
            .collect { (signedIn, reach) ->
                if (signedIn && reach != Reachability.Unreachable && userRepository.getCurrentUser() == null) {
                    userRepository.refreshCurrentUser()
                }
            }
    }
}
