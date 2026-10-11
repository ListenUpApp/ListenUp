package com.calypsan.listenup.client.presentation

import kotlinx.coroutines.flow.SharingStarted

/**
 * How long a screen's upstream collection outlives its last subscriber: long enough to survive a
 * configuration change without restarting, short enough to stop work for a screen that is gone.
 */
internal val WhileSubscribedForUi: SharingStarted = SharingStarted.WhileSubscribed(5_000)
