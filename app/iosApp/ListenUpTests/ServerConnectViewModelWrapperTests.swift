import Testing
import Shared
@testable import ListenUp

@MainActor
@Suite("ServerConnectViewModelWrapper")
struct ServerConnectViewModelWrapperTests {
    // A Local Network denial is the one connect failure the user fixes outside the app: iOS never
    // re-prompts, so the only way back is ListenUp's page in Settings.
    @Test func localNetworkDenialOffersSettings() {
        let denied = ServerConnectErrorLocalNetworkPermissionDenied(correlationId: nil, debugInfo: nil)
        #expect(ServerConnectViewModelWrapper.recovery(for: denied) == .openSettings)
    }

    // An unreachable server is not the permission's fault: sending the user to Settings would be
    // the original bug in reverse.
    @Test func unreachableServerOffersNothing() {
        let unreachable = ServerConnectErrorServerNotReachable(correlationId: nil, debugInfo: nil)
        #expect(ServerConnectViewModelWrapper.recovery(for: unreachable) == nil)
    }

    @Test func invalidUrlOffersNothing() {
        let invalid = ServerConnectErrorInvalidUrl(correlationId: nil, debugInfo: nil, reason: "blank")
        #expect(ServerConnectViewModelWrapper.recovery(for: invalid) == nil)
    }
}
