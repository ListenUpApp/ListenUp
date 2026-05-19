package com.calypsan.listenup.client.testing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * A fake ViewModel used by Phase A boundary tests. Records onCleared() and the
 * SavedStateHandle observed at construction time. Hand-rolled per project memory
 * feedback_fakes_for_seams.md.
 */
class FakeNavViewModel(
    val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    var clearedFlag: Boolean = false
        private set

    override fun onCleared() {
        clearedFlag = true
        super.onCleared()
    }
}
