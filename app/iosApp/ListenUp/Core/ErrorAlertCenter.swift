import Foundation
import SwiftUI

/// One failure the app is telling the user about, as a native value.
///
/// Not a bridged Kotlin type: this reaches a SwiftUI view body, and a bridged object re-bridges
/// every property read on every diff. The `id` is per presentation, not per sentence, so the same
/// failure happening again later is a new alert rather than the one already dismissed.
struct ErrorAlert: Identifiable, Equatable, Sendable {
    let id: UUID
    /// An `AppError.message`: user-facing-quality and period-terminated by contract, so it is shown
    /// verbatim.
    let message: String

    init(id: UUID = UUID(), message: String) {
        self.id = id
        self.message = message
    }
}

/// Holds the error alert on screen, and the ones waiting behind it.
///
/// One per app, created in `RootView`. `GlobalErrorObserver` posts every `AppError` from the shared
/// bus here, and `errorAlertHost(_:)` presents them as a system alert — the iOS way (iosApp rule 10)
/// rather than the Compose snackbar this replaced, which auto-dismissed after four seconds and was
/// never announced to VoiceOver. A failure on the bus is a task the user started that didn't
/// happen; nothing else on screen says so, so it earns the interruption. HIG, Feedback ("Show people
/// when a command can't be carried out") and Alerts.
///
/// **Why a queue.** An alert is modal and shows one at a time. Two failures in a row must not become
/// one: the second waits until the first is dismissed. The same sentence already showing or waiting
/// is not queued again (a retry that fails identically is one alert). The queue is bounded: past
/// `maxQueued` the oldest *waiting* alert is dropped, because the newest is what the user just did.
///
/// **Why dismissing and showing the next are two steps.** Swapping the next alert in during the
/// first one's dismissal would keep the presentation flag true throughout, and SwiftUI does not
/// reliably re-present an alert whose flag never went false. So `dismissCurrent()` clears the slot,
/// and the host calls `showNext()` once the first alert has gone.
@Observable
@MainActor
final class ErrorAlertCenter {
    /// Past this, the oldest waiting alert is dropped. Four is already more than anyone reads.
    static let maxQueued = 4

    private(set) var current: ErrorAlert?
    private var queue: [ErrorAlert] = []

    init() {}

    /// Whether an alert is waiting behind the one showing (or behind an empty slot mid-dismissal).
    var hasWaiting: Bool { !queue.isEmpty }

    /// Show `message`, or queue it behind the alert already showing.
    func post(_ message: String) {
        if current?.message == message || queue.contains(where: { $0.message == message }) { return }
        let alert = ErrorAlert(message: message)
        guard current == nil, queue.isEmpty else {
            queue.append(alert)
            if queue.count > Self.maxQueued { queue.removeFirst() }
            return
        }
        current = alert
    }

    /// The alert showing was dismissed.
    func dismissCurrent() {
        current = nil
    }

    /// Present the next waiting alert, if the slot is free.
    func showNext() {
        guard current == nil, !queue.isEmpty else { return }
        current = queue.removeFirst()
    }
}

private struct ErrorAlertHost: ViewModifier {
    let center: ErrorAlertCenter

    func body(content: Content) -> some View {
        content.alert(
            String(localized: "common.something_went_wrong"),
            isPresented: Binding(
                get: { center.current != nil },
                set: { if !$0 { center.dismissCurrent() } }
            ),
            presenting: center.current
        ) { _ in
            // Informational: nothing to choose, so OK is the right title (HIG, Alerts).
            Button(String(localized: "common.ok"), role: .cancel) {}
        } message: { alert in
            Text(alert.message)
        }
        .task(id: center.current == nil && center.hasWaiting) {
            guard center.current == nil, center.hasWaiting else { return }
            // Let the dismissed alert finish leaving before the next one arrives.
            try? await Task.sleep(for: .milliseconds(400))
            center.showNext()
        }
    }
}

extension View {
    /// Presents `center`'s errors as a system alert. Apply once, on the authenticated shell.
    func errorAlertHost(_ center: ErrorAlertCenter) -> some View {
        modifier(ErrorAlertHost(center: center))
    }
}
