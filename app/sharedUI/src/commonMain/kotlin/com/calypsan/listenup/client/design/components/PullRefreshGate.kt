package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps a pull-to-refresh indicator up for exactly as long as the refresh it started takes.
 *
 * A `PullToRefreshBox` hides its spinner as soon as `isRefreshing` is false, so binding it to a screen's
 * initial-load state makes the spinner vanish the instant the user lets go. [pull] holds [isPulling] true until
 * the suspending refresh returns, success or failure.
 */
@Stable
internal class PullRefreshGate(
    private val scope: CoroutineScope,
) {
    /** True from the moment a pull starts until its refresh has returned. */
    var isPulling: Boolean by mutableStateOf(false)
        private set

    /** Runs [refresh] on the gate's scope with [isPulling] held true for its whole duration. */
    fun pull(refresh: suspend () -> Unit) {
        scope.launch {
            isPulling = true
            try {
                refresh()
            } finally {
                isPulling = false
            }
        }
    }
}

/** Remembers a [PullRefreshGate] bound to this composition's coroutine scope. */
@Composable
internal fun rememberPullRefreshGate(): PullRefreshGate {
    val scope = rememberCoroutineScope()
    return remember(scope) { PullRefreshGate(scope) }
}
