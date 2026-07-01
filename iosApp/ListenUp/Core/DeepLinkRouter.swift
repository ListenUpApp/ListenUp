import SwiftUI
@preconcurrency import Shared

/// Observes the shared `DeepLinkManager.pendingTarget`, resolves it once, and exposes a
/// native Swift outcome the SwiftUI layer reacts to. Invite targets are presented at the
/// root (pre-auth); book targets resolve against the connected server and are pushed by the
/// authenticated tab shell. Bridged Kotlin `ShareTarget`s are mapped to native values here
/// (never handed to a view) per the iOS data-boundary rule.
@Observable
@MainActor
final class DeepLinkRouter {

    /// The resolved, view-facing outcome of the current pending share-link target.
    enum Outcome: Equatable {
        case none
        case claimInvite(serverURL: String, code: String, remoteURL: String?)
        case openBook(id: String)
        case wrongServer
        case notConnected
    }

    private(set) var outcome: Outcome = .none

    private let deepLinkManager: DeepLinkManager
    private let bridge = FlowBridge()

    init(deepLinkManager: DeepLinkManager = Dependencies.shared.deepLinkManager) {
        self.deepLinkManager = deepLinkManager
        bridge.bind(deepLinkManager.pendingTarget) { [weak self] target in
            self?.resolve(target)
        }
    }

    deinit { bridge.cancelAll() }

    /// Decode an incoming universal-link URL and stash it on the shared
    /// seam; the `pendingTarget` binding above drives `resolve`.
    func receive(url: URL) {
        // Redact the query when logging — it carries the invite `code` (a bearer secret)
        // and the server URL. Host + path (`link.listenup.audio/o`) is enough to trace delivery.
        let safe = "\(url.host ?? "?")\(url.path)"
        guard let target = ShareLinkCodec.shared.decode(raw: url.absoluteString) else {
            Log.error("DeepLink: decode returned nil for \(safe)")
            return
        }
        Log.info("DeepLink: decoded a share target from \(safe)")
        deepLinkManager.setPendingTarget(target: target)
    }

    /// Clear the outcome + the shared pending target after the UI has consumed it.
    func consume() {
        outcome = .none
        deepLinkManager.consumeTarget()
    }

    private func resolve(_ target: ShareTarget?) {
        guard let target else { outcome = .none; return }
        switch onEnum(of: target) {
        case .invite(let invite):
            Log.info("DeepLink: resolved invite → presenting claim sheet")
            outcome = .claimInvite(serverURL: invite.serverUrl, code: invite.code, remoteURL: invite.remoteUrl)
        case .book(let book):
            Task { @MainActor [weak self] in await self?.resolveBook(book) }
        case .unknown:
            Log.error("Unexpected ShareTarget case")
            consume()
        }
    }

    private func resolveBook(_ book: ShareTargetBook) async {
        let connectedId = try? await Dependencies.shared.serverConfig.getConnectedServerId()
        switch onEnum(of: ShareTargetResolver.shared.resolve(target: book, connectedInstanceId: connectedId)) {
        case .openBook(let open):
            outcome = .openBook(id: open.bookId.value)
        case .wrongServer:
            outcome = .wrongServer
        case .notConnected:
            outcome = .notConnected
        case .openInviteClaim, .noAccess, .unknown:
            consume()
        }
    }
}
