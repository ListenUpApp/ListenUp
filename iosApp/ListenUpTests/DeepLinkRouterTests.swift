import Testing
@preconcurrency import Shared
@testable import ListenUp

// MARK: - Fake

/// Minimal compiler-satisfying fake for `ServerConfig`. The invite-target test never calls any
/// `ServerConfig` method — the `DeepLinkRouter.resolve` invite branch writes `outcome` directly
/// without touching the config. All stubs `fatalError("unused")` to surface any unexpected call.
///
/// VERIFY ON MAC: `activeUrl` property type (`any KotlinTypedFlow<ServerUrl?>` assumed from the
/// `KotlinTypedFlow` protocol used in `FlowBridge`). `ServerUrl` is a `@JvmInline value class`
/// so it likely exports as a Swift struct — confirm the exact parameter labels when building.
final class FakeServerConfig: ServerConfig {
    var activeUrl: any KotlinTypedFlow<ServerUrl?> { fatalError("unused") }
    func setServerUrl(url: ServerUrl) async { fatalError("unused") }
    func getServerUrl() async -> ServerUrl? { fatalError("unused") }
    func hasServerConfigured() async -> Bool { fatalError("unused") }
    func setRemoteUrl(url: String?) async { fatalError("unused") }
    func getRemoteUrl() async -> ServerUrl? { fatalError("unused") }
    func getActiveUrl() async -> ServerUrl? { fatalError("unused") }
    func switchToFallbackUrl() async -> ServerUrl? { fatalError("unused") }
    func setActiveUrl(url: ServerUrl) async { fatalError("unused") }
    func setConnectedServerId(id: String?) async { fatalError("unused") }
    func getConnectedServerId() async -> String? { nil }
    func updateLocalUrl(url: ServerUrl) async { fatalError("unused") }
    func disconnectFromServer() async { fatalError("unused") }
    func clearAll() async { fatalError("unused") }
}

// MARK: - Tests

/// Pins that a `ShareTargetInvite` target resolves to `.claimInvite` without touching the server
/// config. The book-resolution path (which does call `getConnectedServerId`) is covered by the
/// shared Kotest suite in `ShareTargetResolverTest`.
///
/// VERIFY ON MAC:
/// - `ShareTargetInvite` constructor label names (`serverUrl:`, `code:` expected from Kotlin naming).
/// - `DeepLinkRouter.Outcome` `==` works (it's a pure Swift enum with `Equatable` — should be fine).
/// - The 50 ms `Task.sleep` is sufficient under CI load; increase if the suite flakes on a
///   CPU-starved runner (the flow emission is synchronous from Kotlin's StateFlow, but the Swift
///   async scheduler needs at least one suspension point to propagate it).
@MainActor
struct DeepLinkRouterTests {

    @Test func inviteTargetMapsToClaimInvite() async throws {
        let manager = DeepLinkManager()
        let router = DeepLinkRouter(deepLinkManager: manager, serverConfig: FakeServerConfig())

        manager.setPendingTarget(target: ShareTargetInvite(serverUrl: "https://lib.example.com", code: "JOIN9"))
        try await Task.sleep(for: .milliseconds(50))

        #expect(router.outcome == .claimInvite(serverURL: "https://lib.example.com", code: "JOIN9"))
    }
}
