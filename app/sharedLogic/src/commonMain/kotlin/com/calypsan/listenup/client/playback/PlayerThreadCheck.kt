package com.calypsan.listenup.client.playback

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Tells [PlayerSession] whether the caller is already on its scope's thread. Production carries none: the session
 * asks the scope's dispatcher (`Main.immediate` needs no dispatch on the main thread). A spec's scheduler always
 * dispatches, so a spec puts one on the session's scope to say its body is the player's thread.
 */
internal class PlayerThreadCheck(
    val isCurrent: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    /** The context key. */
    companion object Key : CoroutineContext.Key<PlayerThreadCheck>
}
