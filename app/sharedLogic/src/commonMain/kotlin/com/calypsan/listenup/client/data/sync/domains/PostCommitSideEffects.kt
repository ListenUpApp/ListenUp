package com.calypsan.listenup.client.data.sync.domains

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Collects side effects that must run only AFTER the enclosing apply transaction commits.
 *
 * A [MirrorApply] runs inside [applyEventAtomically]'s IMMEDIATE write transaction, so a
 * file-system or network side effect it performs inline (e.g. deleting a stale cover file)
 * still happens even when a LATER write in the same aggregate throws and rolls the whole
 * transaction back — leaving Room's committed state and the on-disk state out of sync.
 * Registering the effect here defers it past commit: [applyEventAtomically] runs the
 * collected actions once the transaction succeeds, and never on rollback.
 *
 * The collector is confined to one apply's coroutine call chain (installed per
 * [applyEventAtomically] call), so its mutable list needs no synchronization.
 */
internal class PostCommitSideEffects : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PostCommitSideEffects>

    private val actions = mutableListOf<suspend () -> Unit>()

    fun add(action: suspend () -> Unit) {
        actions += action
    }

    /** Run every registered action in registration order. Called only after a successful commit. */
    suspend fun runAll() {
        actions.forEach { it() }
    }
}

/**
 * Register [action] to run after the current apply transaction commits. Falls back to running
 * it inline when no collector is installed — a [MirrorApply] invoked outside
 * [applyEventAtomically] (e.g. a direct unit test) has no transaction to defer past. When a
 * collector IS present, [action] never runs if the transaction rolls back.
 */
internal suspend fun deferUntilCommit(action: suspend () -> Unit) {
    when (val collector = coroutineContext[PostCommitSideEffects]) {
        null -> action()
        else -> collector.add(action)
    }
}
