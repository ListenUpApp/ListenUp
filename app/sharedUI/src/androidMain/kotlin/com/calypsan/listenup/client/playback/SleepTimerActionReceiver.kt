package com.calypsan.listenup.client.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** What the sleep notification's buttons do: the player's own sleep commands. */
interface SleepTimerControls {
    /** Adds [minutes] to a running duration timer. */
    fun extendSleepTimer(minutes: Int)

    /** Cancels the timer; during the fade, restores full volume (Keep listening). */
    fun cancelSleepTimer()
}

/** Does what [action], one of [SleepTimerActionReceiver]'s actions, asks of [controls]. Unknown actions do nothing. */
internal fun handleSleepTimerAction(
    action: String?,
    controls: SleepTimerControls,
) {
    when (action) {
        SleepTimerActionReceiver.ACTION_ADD_TEN -> {
            controls.extendSleepTimer(SleepTimerActionReceiver.ADD_MINUTES)
        }

        SleepTimerActionReceiver.ACTION_CANCEL, SleepTimerActionReceiver.ACTION_KEEP_LISTENING -> {
            controls.cancelSleepTimer()
        }
    }
}

/**
 * Receives Add 10 min, Cancel and Keep listening from the sleep notification ([SleepTimerNotifier]) and hands them to
 * the player. The notification then follows the timer's new state on its own.
 *
 * Not exported (see the manifest): these intents come from our own [android.app.PendingIntent] and nowhere else.
 */
class SleepTimerActionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val controls: SleepTimerControls by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        handleSleepTimerAction(intent.action, controls)
    }

    /** The notification's actions. */
    companion object {
        /** Ten more minutes on a duration timer. */
        const val ACTION_ADD_TEN = "com.calypsan.listenup.action.SLEEP_ADD_TEN"

        /** Cancel the timer. */
        const val ACTION_CANCEL = "com.calypsan.listenup.action.SLEEP_CANCEL"

        /** Cancel during the fade, which restores full volume. */
        const val ACTION_KEEP_LISTENING = "com.calypsan.listenup.action.SLEEP_KEEP_LISTENING"

        /** What Add 10 min adds. */
        const val ADD_MINUTES = 10
    }
}
