package com.calypsan.listenup.client.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private class RecordingViewModel : ViewModel() {
    var clearedCount = 0
    val running = viewModelScope.launch { awaitCancellation() }

    override fun onCleared() {
        clearedCount++
    }
}

/** Tests for [close] — clearing a ViewModel the way a `ViewModelStore` would. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelCloseTest :
    FunSpec({

        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("close cancels viewModelScope") {
            runTest {
                val viewModel = RecordingViewModel()

                viewModel.close()

                viewModel.running.isCancelled shouldBe true
            }
        }

        test("close runs onCleared") {
            runTest {
                val viewModel = RecordingViewModel()

                viewModel.close()

                viewModel.clearedCount shouldBe 1
            }
        }

        test("closing twice cancels once and does not throw") {
            runTest {
                val viewModel = RecordingViewModel()

                viewModel.close()
                viewModel.close()

                viewModel.running.isCancelled shouldBe true
                // The library runs onCleared on every clear, so a side-effecting onCleared must guard itself.
                viewModel.clearedCount shouldBe 2
            }
        }
    })
