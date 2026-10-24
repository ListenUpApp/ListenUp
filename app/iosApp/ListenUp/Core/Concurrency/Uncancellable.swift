/// Runs `operation` to completion even if the calling task is cancelled — Swift's counterpart to
/// Kotlin's `NonCancellable`, for the few teardown writes that must land (Android:
/// `PlaybackService.saveCurrentPositionBlocking`). A Swift-Export-bridged Kotlin suspend cancels its
/// coroutine the moment the calling task is cancelled, so without this a teardown save can be cut off.
///
/// An unstructured task does not inherit the caller's cancellation, and awaiting its value does not
/// cancel it, so the operation never sees the caller's cancellation.
///
/// Not `withTaskCancellationShield` (Swift 6.4): it is `@_alwaysEmitIntoClient`, but its inlined body
/// strongly references `swift_task_cancellationShieldPop`, which iOS 26's Swift runtime lacks — so even
/// behind `#available(iOS 27.0, *)` the app fails to launch on iOS 26 (dyld: "Symbol not found").
/// Switch to the shield when the deployment target reaches iOS 27.
///
/// Teardown writes only: never wrap anything a user can cancel.
func uncancellable<Value: Sendable>(_ operation: @escaping @Sendable () async -> Value) async -> Value {
    await Task { await operation() }.value
}
