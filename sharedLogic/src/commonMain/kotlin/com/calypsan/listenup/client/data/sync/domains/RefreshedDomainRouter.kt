package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

/**
 * Routes a nudge [SyncControl] to its declared [RefreshStrategy], derived from the
 * catalog's [RefreshedDomain] entries. Replaces the four ad-hoc nudge lambdas that
 * used to live in the DI module: one table, one runner, all driven by the catalog.
 */
internal class RefreshedDomainRouter(
    refreshed: List<RefreshedDomain>,
) {
    // Trigger distinctness is not enforced here (a collision would silently keep the
    // last entry); the completeness spec asserts the catalog's triggers are distinct.
    private val byTrigger: Map<KClass<out SyncControl>, RefreshStrategy> =
        refreshed.associate { it.trigger to it.refresh }

    /**
     * Run the declared refresh for [control]. Returns `true` if a refreshed domain
     * claimed it, `false` if it is an engine/lifecycle control the dispatcher must
     * handle itself.
     */
    suspend fun dispatch(control: SyncControl): Boolean {
        val strategy = byTrigger[control::class] ?: return false
        logger.debug { "Nudge $control claimed by a refreshed domain; running ${strategy::class.simpleName} refresh" }
        when (strategy) {
            is RefreshStrategy.Ping -> strategy.ping()
            is RefreshStrategy.Refetch -> runRefetchSafely(strategy.refetch)
        }
        return true
    }

    private suspend fun runRefetchSafely(refetch: suspend () -> Unit) {
        try {
            refetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Nudge refetch failed; continuing" }
        }
    }
}
