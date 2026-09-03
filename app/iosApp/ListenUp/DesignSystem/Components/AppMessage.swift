import Foundation
import SwiftUI

/// One transient sentence the app says and then stops saying.
///
/// A native value, not a bridged Kotlin type: this reaches a SwiftUI view body, and a bridged
/// object re-bridges every property read on every diff (the hazard that froze the library grid).
struct AppMessage: Identifiable, Equatable, Sendable {
    /// Whether this is something that went wrong, or something that went right.
    ///
    /// Only two, deliberately. A third ("warning") would need a rule for when it applies, and there
    /// is no such rule — every caller knows which of these two it has.
    enum Kind: Sendable {
        case info
        case error
    }

    let id: UUID
    let text: String
    let kind: Kind

    init(id: UUID = UUID(), text: String, kind: Kind) {
        self.id = id
        self.text = text
        self.kind = kind
    }

    /// A confirmation — something the user did, landed.
    static func info(_ text: String) -> AppMessage { AppMessage(text: text, kind: .info) }

    /// A failure. The text is an `AppError.message`, which the error architecture defines as
    /// user-facing-quality and period-terminated, so it is shown verbatim.
    static func error(_ text: String) -> AppMessage { AppMessage(text: text, kind: .error) }
}

/// Holds the message currently being shown, and the ones waiting.
///
/// One per app, created in `RootView` and injected through the environment — the iOS counterpart of
/// Compose's single `snackbarHostState` in `AppShell`, which both `GlobalErrorSnackbar` and the
/// bulk-edit confirmation raise onto.
///
/// **Why a queue rather than last-write-wins.** Two bulk actions in a row is a normal thing to do,
/// and a second message overwriting the first mid-display means the user is told about the second
/// thing and never about the first. The queue is bounded: past `maxQueued` the oldest *waiting*
/// message is dropped rather than the newest, because the most recent action is the one the user is
/// still looking at.
@Observable
@MainActor
final class AppMessageCenter {
    /// How long a message stays up. Matches Material's `SnackbarDuration.Short`, so a reader moving
    /// between platforms gets the same amount of time to read the same sentence.
    static let displayDuration: Duration = .seconds(4)

    /// Past this, the oldest waiting message is dropped. Four is already more than anyone reads.
    static let maxQueued = 4

    private(set) var current: AppMessage?

    private var queue: [AppMessage] = []
    private var dismissTask: Task<Void, Never>?

    init() {}

    /// Show `message`, or queue it behind one already showing.
    func post(_ message: AppMessage) {
        guard current != nil else {
            show(message)
            return
        }
        queue.append(message)
        if queue.count > Self.maxQueued { queue.removeFirst() }
    }

    /// Dismiss what is showing and advance to whatever is waiting. The swipe gesture and the
    /// auto-dismiss timer both come through here, so there is one path out of a message.
    func dismissCurrent() {
        dismissTask?.cancel()
        dismissTask = nil
        current = nil
        guard !queue.isEmpty else { return }
        show(queue.removeFirst())
    }

    private func show(_ message: AppMessage) {
        current = message
        dismissTask?.cancel()
        dismissTask = Task { [weak self] in
            try? await Task.sleep(for: Self.displayDuration)
            guard !Task.isCancelled else { return }
            self?.dismissCurrent()
        }
    }
}
