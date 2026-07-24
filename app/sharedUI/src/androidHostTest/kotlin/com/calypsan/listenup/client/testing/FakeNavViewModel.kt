package com.calypsan.listenup.client.testing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * A fake ViewModel for boundary tests. Records onCleared() and the
 * SavedStateHandle observed at construction time. Hand-rolled as a fake with real
 * in-memory state rather than a mock.
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
