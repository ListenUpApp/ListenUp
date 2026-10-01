package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * `hardcover_preferences`: each listener's choices about Hardcover sync — today, when ListenUp updates
 * Hardcover ([HardcoverShareMode]). Kept apart from `hardcover_connections` because reconnecting replaces
 * that row, and a disconnect keeps it: it is the listener's choice, not the connection's. Read through
 * [hardcoverShareMode]. Every change is announced on [HardcoverSyncActivity.preferencesChanged], so every
 * watching client's Connected is republished with it.
 */
class HardcoverPreferences(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val activity: HardcoverSyncActivity? = null,
) {
    /**
     * Records [mode] as [userId]'s choice. Switching to [HardcoverShareMode.FINISHED_ONLY] drops, in the
     * same transaction, the START and PROGRESS rows still queued: they describe listening the listener no
     * longer wants shared. FINISH rows stay, open reads stay recorded (so a later FINISH closes the read it
     * belongs to), and nothing already on Hardcover changes. A row the push lane is sending at this moment
     * may still land; nothing is queued after the switch. Idempotent.
     */
    suspend fun setShareMode(
        userId: String,
        mode: HardcoverShareMode,
    ) {
        val at = clock.now().toEpochMilliseconds()
        suspendTransaction(sql) {
            sql.hardcoverPreferencesQueries.upsertShareMode(user_id = userId, share_mode = mode.name, updated_at = at)
            if (mode == HardcoverShareMode.FINISHED_ONLY) {
                sql.hardcoverOutboxQueries.deleteStartsAndProgressForUser(user_id = userId)
            }
        }
        activity?.preferencesChanged(userId)
    }
}

/**
 * [userId]'s [HardcoverShareMode]: [HardcoverShareMode.AS_I_LISTEN] when they never chose. A stored value
 * this build doesn't know (written by a newer server, then downgraded) reads as
 * [HardcoverShareMode.FINISHED_ONLY]: it shares less, never more.
 */
internal suspend fun ListenUpDatabase.hardcoverShareMode(userId: String): HardcoverShareMode {
    val stored =
        suspendTransaction<String?>(this) { hardcoverPreferencesQueries.selectShareMode(userId).executeAsOneOrNull() }
            ?: return HardcoverShareMode.AS_I_LISTEN
    return HardcoverShareMode.entries.firstOrNull { it.name == stored } ?: HardcoverShareMode.FINISHED_ONLY
}
