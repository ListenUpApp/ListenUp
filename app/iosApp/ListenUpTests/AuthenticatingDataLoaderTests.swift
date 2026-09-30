import Foundation
import Nuke
import os
import Testing
@testable import ListenUp

/// Pins where the access token goes: only to the connected server (scheme, host and port), only when
/// the request doesn't already carry its own `Authorization`, and never to a third-party image URL.
@Suite("AuthenticatingDataLoader")
struct AuthenticatingDataLoaderTests {
    private let base = URL(string: "https://listen.example:8443")

    private func request(_ string: String, authorization: String? = nil) -> URLRequest {
        var request = URLRequest(url: URL(string: string)!)
        if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
        return request
    }

    @Test func serverRequestsNeedTheToken() {
        #expect(AuthenticatingDataLoader.needsAuthorization(
            request("https://listen.example:8443/api/v1/covers/b1?v=h"), trustedBase: base
        ))
    }

    @Test func otherHostsNeverGetTheToken() {
        #expect(!AuthenticatingDataLoader.needsAuthorization(
            request("https://images.example.com/api/v1/covers/b1"), trustedBase: base
        ))
        #expect(!AuthenticatingDataLoader.needsAuthorization(
            request("https://listen.example/api/v1/covers/b1"), trustedBase: base
        ))
        #expect(!AuthenticatingDataLoader.needsAuthorization(
            request("http://listen.example:8443/api/v1/covers/b1"), trustedBase: base
        ))
    }

    @Test func noTrustedServerMeansNoToken() {
        #expect(!AuthenticatingDataLoader.needsAuthorization(
            request("https://listen.example:8443/api/v1/covers/b1"), trustedBase: nil
        ))
    }

    @Test func anExistingAuthorizationIsLeftAlone() {
        #expect(!AuthenticatingDataLoader.needsAuthorization(
            request("https://listen.example:8443/api/v1/avatars/u1", authorization: "Bearer t"), trustedBase: base
        ))
    }

    @Test func localFilesAreNeverAuthenticated() {
        #expect(!AuthenticatingDataLoader.needsAuthorization(
            URLRequest(url: URL(fileURLWithPath: "/covers/a.jpg")), trustedBase: base
        ))
    }

    /// End to end through `loadData`: the inner loader sees the bearer header on a server request,
    /// and the request untouched on a foreign one.
    @Test func loadDataAttachesTheBearerForTheServerOnly() async {
        let inner = RecordingLoader()
        let loader = AuthenticatingDataLoader(inner: inner, token: { "tok" })
        loader.trust(base: base)

        await withCheckedContinuation { continuation in
            _ = loader.loadData(
                with: request("https://listen.example:8443/api/v1/covers/b1"),
                didReceiveData: { _, _ in },
                completion: { _ in continuation.resume() }
            )
        }
        await withCheckedContinuation { continuation in
            _ = loader.loadData(
                with: request("https://images.example.com/x.jpg"),
                didReceiveData: { _, _ in },
                completion: { _ in continuation.resume() }
            )
        }

        #expect(inner.authorizations == ["Bearer tok", nil])
    }

    @Test func noTokenFailsWithoutLoading() async {
        let inner = RecordingLoader()
        let loader = AuthenticatingDataLoader(inner: inner, token: { nil })
        loader.trust(base: base)

        let error: Error? = await withCheckedContinuation { continuation in
            _ = loader.loadData(
                with: request("https://listen.example:8443/api/v1/covers/b1"),
                didReceiveData: { _, _ in },
                completion: { continuation.resume(returning: $0) }
            )
        }

        #expect((error as? URLError)?.code == .userAuthenticationRequired)
        #expect(inner.authorizations.isEmpty)
    }
}

/// Records each request's `Authorization` header and completes at once.
private final class RecordingLoader: DataLoading {
    private let recorded = OSAllocatedUnfairLock<[String?]>(initialState: [])

    var authorizations: [String?] { recorded.withLock { $0 } }

    func loadData(
        with request: URLRequest,
        didReceiveData: @escaping @Sendable (Data, URLResponse) -> Void,
        completion: @escaping @Sendable (Error?) -> Void
    ) -> any Cancellable {
        recorded.withLock { $0.append(request.value(forHTTPHeaderField: "Authorization")) }
        completion(nil)
        return NoopCancellable()
    }
}

private final class NoopCancellable: Cancellable {
    func cancel() {}
}
