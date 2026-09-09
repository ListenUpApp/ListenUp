package com.calypsan.listenup.client.test

/**
 * The failure a test injects into a fake to drive an error path.
 *
 * Every call site used to throw a bare `RuntimeException("boom")`. Naming the type says the throw
 * is deliberate scaffolding rather than a real fault escaping the code under test, and it means a
 * stack trace in a failing run distinguishes the two at a glance. It also keeps the codebase to one
 * answer for "what do I throw here", instead of fifteen files each picking their own.
 *
 * @param detail what the fake is pretending went wrong, in the words of the case that injects it.
 */
class SimulatedFailure(
    detail: String,
) : RuntimeException(detail)
