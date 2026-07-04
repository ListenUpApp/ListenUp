package com.calypsan.listenup.client.data.sync.domains

/**
 * Pure logic for the nudge-recovery completeness guard (Plan §6a).
 *
 * The critical invariant: **every [RefreshedDomain]'s trigger must be wired to an EXPLICIT
 * (non-`else`) arm of the engine's recovery dispatch, `runNudgeLifecycleRecovery`.** A nudge frame
 * is lossy; the lifecycle-reconcile pass is what heals a dropped one. A new refreshed domain whose
 * trigger the engine forgot to wire falls into that function's `else -> logger.warn(...)` arm — the
 * recovery silently never runs, and CI stays green because the warn is a runtime log, not a failure.
 *
 * This object parses `SyncEngine.kt` for the trigger classes handled by non-`else` arms so the guard
 * can assert coverage against the real runtime catalog. It carries no Konsist/runtime dependency, so
 * its parsing is exercised directly on synthetic input by [NudgeRecoveryDispatchGuardFixtureTest].
 */
internal object NudgeRecoveryDispatchGuard {
    /**
     * The `SyncControl` subtype simpleNames that `runNudgeLifecycleRecovery` handles in an explicit
     * `when (domain.trigger)` arm. The `else` arm carries no `SyncControl.X::class` reference, so it
     * contributes nothing — a trigger absent from this set would route only through `else`.
     */
    fun handledTriggerNames(syncEngineSource: String): Set<String> {
        val body = extractDispatchWhenBody(syncEngineSource) ?: return emptySet()
        return TRIGGER_CLASS_REGEX
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * The body between the braces of `runNudgeLifecycleRecovery`'s `when (domain.trigger)`, or null
     * if the function or its dispatch `when` is absent (a shape change the sanity assertion catches).
     */
    private fun extractDispatchWhenBody(source: String): String? {
        val fn = source.indexOf("fun runNudgeLifecycleRecovery")
        if (fn < 0) return null
        val marker = source.indexOf("when (domain.trigger)", fn)
        if (marker < 0) return null
        val open = source.indexOf('{', marker)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private val TRIGGER_CLASS_REGEX = Regex("""SyncControl\.(\w+)::class""")
}
