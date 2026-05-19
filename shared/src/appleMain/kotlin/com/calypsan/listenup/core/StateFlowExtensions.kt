package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Collect StateFlow values from Swift using a callback.
 * Returns a Job that can be cancelled to stop collection.
 *
 * This extension enables memory-safe observation of Kotlin StateFlows from Swift
 * without requiring SKIE or additional dependencies. The collection happens on
 * the Main dispatcher to ensure all Swift UI updates are thread-safe.
 *
 * Usage from Swift:
 * ```swift
 * let job = viewModel.state.collect { state in
 *     self.handleState(state)
 * }
 * // Later, when done observing:
 * job.cancel(cause: nil)
 * ```
 *
 * Memory Safety:
 * - Always use `[weak self]` in the Swift callback to prevent retain cycles
 * - Cancel the returned Job in the Swift wrapper's `deinit`
 *
 * @param onEach Callback invoked for each state emission (runs on Main thread)
 * @return Job that can be cancelled to stop collection
 */
fun <T> StateFlow<T>.collect(onEach: (T) -> Unit): Job =
    CoroutineScope(Dispatchers.Main).launch {
        collect { value ->
            onEach(value)
        }
    }
