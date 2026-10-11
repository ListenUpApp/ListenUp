import ListenUpActivityKit
import UIKit

/// What to do with a tapped quick action's book, given where sign-in stands.
enum QuickActionDisposition: Equatable {
    /// The session is not settled yet (a cold launch); keep the book pending.
    case wait
    /// Start this book.
    case play(bookId: String)
    /// Signed out: just open the app. Playing after a later login would surprise the person.
    case drop
}

/// Holds a tapped "play this book" quick action until sign-in is known, then plays it (or drops it).
///
/// One per process, like `DeepLinkRouter`: the app delegate and the window scene delegate receive the
/// tap before any view exists, and `RootView` resolves it once the auth state is known.
@Observable
@MainActor
final class QuickActionRouter {
    static let shared = QuickActionRouter()

    /// The book of a tap not yet resolved.
    private(set) var pendingBookId: String?

    private let bundleIdentifier: String

    init(bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "com.calypsan.listenup.client.ios") {
        self.bundleIdentifier = bundleIdentifier
    }

    /// Records a tapped item. False when it is not one of ours (the system still opens the app).
    @discardableResult
    func receive(_ item: UIApplicationShortcutItem) -> Bool {
        guard let bookId = RecentBookQuickActions.bookId(from: item, bundleIdentifier: bundleIdentifier) else {
            return false
        }
        pendingBookId = bookId
        return true
    }

    /// THE decision: wait while the session is settling, play when signed in (a lapsed session keeps
    /// its library, so playback still works), drop when signed out.
    nonisolated static func disposition(pendingBookId: String?, authState: AuthStateKind) -> QuickActionDisposition {
        guard let pendingBookId else { return .wait }
        switch authState {
        case .initializing, .checkingServer: return .wait
        case .authenticated, .sessionLapsed: return .play(bookId: pendingBookId)
        case .needsServerUrl, .needsSetup, .needsLogin, .pendingApproval: return .drop
        }
    }

    /// Acts on the pending tap, if the auth state allows: plays through `playback` or discards it.
    func resolve(authState: AuthStateKind, playback: any PlaybackControlling) {
        switch Self.disposition(pendingBookId: pendingBookId, authState: authState) {
        case .wait:
            break
        case .play(let bookId):
            pendingBookId = nil
            playback.playBook(id: bookId)
        case .drop:
            pendingBookId = nil
        }
    }
}
