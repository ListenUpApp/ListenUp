import Foundation
import Nuke
import os
@preconcurrency import Shared

/// Nuke's data loader for ListenUp images: attaches `Authorization: Bearer <token>` at load time to
/// requests for the connected server, and passes everything else through untouched.
///
/// Authenticating here, rather than baking the header into each `ImageRequest`, lets a cover's request
/// be built synchronously (see `CoverImageRequest`) and keeps the token out of every cache key. The
/// token is only ever sent to the trusted server's scheme, host and port, never to a third-party image
/// URL. A request that already carries an `Authorization` header (contributor photos and avatars still
/// build theirs) is left alone.
final class AuthenticatingDataLoader: DataLoading {
    static let shared = AuthenticatingDataLoader(
        inner: {
            // The data cache replaces URLCache (Nuke's `withDataCache` shape): one disk cache, keyed on
            // the token-free image identity.
            let configuration = URLSessionConfiguration.default
            configuration.urlCache = nil
            return DataLoader(configuration: configuration)
        }(),
        token: { await AuthenticatingDataLoader.freshToken() }
    )

    private let inner: any DataLoading
    private let token: @Sendable () async -> String?
    private let trustedBase = OSAllocatedUnfairLock<URL?>(initialState: nil)

    init(inner: any DataLoading, token: @escaping @Sendable () async -> String?) {
        self.inner = inner
        self.token = token
    }

    /// The server whose requests get the access token; nil trusts none.
    func trust(base: URL?) {
        trustedBase.withLock { $0 = base }
    }

    /// Whether `request` is bound for `trustedBase` and doesn't carry its own `Authorization` yet.
    /// Matches scheme, host and port exactly, so the token never leaves the connected server.
    static func needsAuthorization(_ request: URLRequest, trustedBase: URL?) -> Bool {
        guard let url = request.url, let trustedBase,
              request.value(forHTTPHeaderField: "Authorization") == nil
        else { return false }
        return url.scheme?.lowercased() == trustedBase.scheme?.lowercased()
            && url.host?.lowercased() == trustedBase.host?.lowercased()
            && url.port == trustedBase.port
    }

    func loadData(
        with request: URLRequest,
        didReceiveData: @escaping @Sendable (Data, URLResponse) -> Void,
        completion: @escaping @Sendable (Error?) -> Void
    ) -> any Cancellable {
        guard Self.needsAuthorization(request, trustedBase: trustedBase.withLock { $0 }) else {
            return inner.loadData(with: request, didReceiveData: didReceiveData, completion: completion)
        }
        let load = PendingLoad()
        let inner = inner
        let token = token
        load.start {
            // freshAccessToken refreshes an expired token: the image URLSession bypasses the RPC
            // channel's 401-heal, so a stale token would 401 with no retry.
            guard let bearer = await token() else {
                // No token (signed out, or offline with an expired one): fail rather than fire a
                // request the server can only answer with 401. The view falls back to the local file.
                completion(URLError(.userAuthenticationRequired))
                return
            }
            guard !Task.isCancelled else { return }
            var authorized = request
            authorized.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
            load.attach(inner.loadData(with: authorized, didReceiveData: didReceiveData, completion: completion))
        }
        return load
    }

    @MainActor
    private static func freshToken() async -> String? {
        try? await KoinHelper.shared.freshAccessToken()
    }
}

/// One authenticated load: the token fetch, then the inner load it starts. Cancelling either stage
/// cancels the load; a cancel that lands between them cancels the inner load as it attaches.
private final class PendingLoad: Cancellable {
    private struct State {
        var isCancelled = false
        var task: Task<Void, Never>?
        var inner: (any Cancellable)?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    func start(_ work: @escaping @Sendable () async -> Void) {
        let task = Task { await work() }
        let cancelNow = state.withLock { state -> Bool in
            state.task = task
            return state.isCancelled
        }
        if cancelNow { task.cancel() }
    }

    func attach(_ cancellable: any Cancellable) {
        let cancelNow = state.withLock { state -> Bool in
            state.inner = cancellable
            return state.isCancelled
        }
        if cancelNow { cancellable.cancel() }
    }

    func cancel() {
        let (task, inner) = state.withLock { state -> (Task<Void, Never>?, (any Cancellable)?) in
            state.isCancelled = true
            return (state.task, state.inner)
        }
        task?.cancel()
        inner?.cancel()
    }
}
