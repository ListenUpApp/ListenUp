package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Closes the gap a catch-up pass leaves in a domain with no digest backstop (playback positions).
 *
 * The firehose resumes from the highest revision of any domain, but a pull is just "rows above my cursor", taken one
 * domain after another. A row written to a digest-less domain after its pull and before the pass ends sits above that
 * domain's cursor and below the resume point: the stream never replays it, and no digest comparison heals it.
 *
 * So, around connecting: [armHolds] first, so a live frame applies but cannot advance the cursor past the gap; then,
 * once connected, [topUp] pulls each such domain again. The stream delivers everything above the resume point and the
 * top-up fetches everything at or below it that the pass missed. The hold lifts by the dispatcher's own rule the first
 * time a pull moves the cursor.
 */
internal class UnbackedDomainTopUp(
    private val registry: ClientSyncDomainRegistry,
    private val catchUp: CatchUp,
    private val hold: (domainName: String) -> Unit,
) {
    private fun unbackedHandlers(): List<SyncDomainHandler<*>> =
        registry.registeredDomains().mapNotNull(registry::lookup).filterNot { it.hasDigestBackstop }

    /** Holds the cursor of every domain with no digest backstop until a pull moves it. Call before connecting. */
    fun armHolds() {
        unbackedHandlers().forEach { hold(it.domainName) }
    }

    /** Pulls every domain with no digest backstop once more. Call after connecting. A failed pull keeps its hold. */
    suspend fun topUp() {
        unbackedHandlers().forEach { handler ->
            @Suppress("UNCHECKED_CAST")
            val result = catchUp.catchUp(handler as SyncDomainHandler<Any>)
            if (result is AppResult.Failure) {
                logger.warn {
                    "Top-up pull failed for ${handler.domainName}: ${result.error.code}; its cursor stays held"
                }
            }
        }
    }
}
