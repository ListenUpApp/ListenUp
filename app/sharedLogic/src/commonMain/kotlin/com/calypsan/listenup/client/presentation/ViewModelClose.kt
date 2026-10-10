package com.calypsan.listenup.client.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

private const val CLOSE_KEY = "close"

/**
 * Clears this ViewModel exactly as the `ViewModelStore` that owns it on Android would: cancels
 * `viewModelScope`, closes its closeables, and runs `onCleared`.
 *
 * For an owner on a platform with no store — the iOS observers, through `KoinHelper.closeViewModel` —
 * and for tests. Android never calls it; the web client clears a store of its own per session.
 */
internal fun ViewModel.close() {
    ViewModelStore().apply { put(CLOSE_KEY, this@close) }.clear()
}
