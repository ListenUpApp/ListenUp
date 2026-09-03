import Foundation
import SwiftUI
@preconcurrency import Shared

/// Turns every `AppError` on the shared bus into a message on screen.
///
/// The iOS counterpart of Compose's `GlobalErrorSnackbar`, mounted in the same place in the
/// hierarchy: on the authenticated shell, not the app root. The pre-auth screens — server connect,
/// login, register, claim invite — already render their failures inline with `ErrorBanner`, so a
/// root-level consumer would say everything twice before the user is even signed in.
///
/// Before this existed, `ErrorBus` had ~106 `emit` sites across the shared code and not one Swift
/// consumer, so every ViewModel following the canonical `errorBus.emit(result.error)` failure branch
/// produced a snackbar on Android and nothing at all here. That was invisible in review, because the
/// ViewModel looked correct.
///
/// **One accepted divergence from Compose.** Compose enriches `AuthError.RateLimited` with the
/// concrete `retryAfterSeconds`, and iOS shows that subtype's constant instead ("Too many attempts.
/// Try again later."). `AuthError` and its subtypes are deliberately absent from the Swift Export
/// surface — only the `AppError` interface is exported — so Swift cannot name the type to read the
/// field, and widening the export surface for one sentence is the wrong trade. Less specific, never
/// wrong.
@Observable
@MainActor
final class GlobalErrorObserver {
    private let bridge = FlowBridge()

    init(center: AppMessageCenter, errorBus: ErrorBus = Dependencies.shared.errorBus) {
        bridge.bind(errorBus.errors) { [weak center] error in
            // Logged as well as shown: the on-screen sentence is for the reader, the log line is for
            // whoever has to find out why. `debugInfo` carries the per-instance technical detail that
            // `message` deliberately does not.
            Log.error("AppError [\(error.code)] \(error.message) — \(error.debugInfo ?? "no debug info")")
            center?.post(.error(error.message))
        }
    }

    deinit { bridge.cancelAll() }   // nonisolated-safe; see FlowBridge.
}
