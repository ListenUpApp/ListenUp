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

    // After a Bonjour denial the notice shows before the user tries — unless the typed address is
    // clearly remote, which the permission cannot be blocking.
    @Test func upFrontNoticeShowsForAnAddressThatMightBeLocal() {
        #expect(ServerConnectViewModelWrapper.showsLocalNetworkNotice(
            recovery: nil, deniedByDiscovery: true, hasError: false, isClearlyRemote: false
        ))
    }

    @Test func upFrontNoticeStepsAsideForAClearlyRemoteAddress() {
        #expect(!ServerConnectViewModelWrapper.showsLocalNetworkNotice(
            recovery: nil, deniedByDiscovery: true, hasError: false, isClearlyRemote: true
        ))
    }

    @Test func aBlockedConnectAlwaysShowsTheNotice() {
        #expect(ServerConnectViewModelWrapper.showsLocalNetworkNotice(
            recovery: .openSettings, deniedByDiscovery: false, hasError: true, isClearlyRemote: true
        ))
    }

    @Test func noDenialNoNotice() {
        #expect(!ServerConnectViewModelWrapper.showsLocalNetworkNotice(
            recovery: nil, deniedByDiscovery: false, hasError: false, isClearlyRemote: false
        ))
    }

    @Test func anOrdinaryFailureHidesTheUpFrontNotice() {
        #expect(!ServerConnectViewModelWrapper.showsLocalNetworkNotice(
            recovery: nil, deniedByDiscovery: true, hasError: true, isClearlyRemote: false
        ))
    }
}
