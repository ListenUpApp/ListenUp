import Foundation

/// Something the shared player asked the UI to do once: open a book, the Inbox, a PDF, or the sign-in flow.
enum PlayerRequest: Equatable {
    case openBook(String)
    case openInbox
    case openDocument(ReaderDocument)
    case signIn
}

/// Holds the player's one-shot requests until one window claims them.
///
/// The player is a process-wide singleton, but with several iPad windows each has a shell: a request
/// must act in exactly one of them — the window most recently in front — the way `PushTapRouter` hands
/// a notification tap to one window. Each shell reports when its window becomes active and claims;
/// the router hands the request to one claimant only.
@Observable
@MainActor
final class PlayerRequestRouter {
    /// The request waiting for a window. A newer request replaces an unclaimed older one: the last
    /// thing the listener asked for is the one they want.
    private(set) var pending: PlayerRequest?

    /// The window most recently brought to the front.
    private(set) var frontSceneID: UUID?

    func post(_ request: PlayerRequest) { pending = request }

    func sceneBecameActive(_ sceneID: UUID) { frontSceneID = sceneID }

    /// A window's shell went away; if it was the front one, the next claimant may take requests.
    func sceneWentAway(_ sceneID: UUID) {
        if frontSceneID == sceneID { frontSceneID = nil }
    }

    /// Hands the pending request to `sceneID` — and clears it — when that window is the one to act on it.
    func claim(for sceneID: UUID) -> PlayerRequest? {
        guard let pending, FrontWindow.receives(sceneID, frontSceneID: frontSceneID) else { return nil }
        self.pending = nil
        return pending
    }
}
