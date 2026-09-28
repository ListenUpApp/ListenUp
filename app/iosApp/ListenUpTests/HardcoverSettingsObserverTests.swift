import Foundation
import Testing
import Shared
@testable import ListenUp

// The Hardcover screen's boundary mappings: every shared UI state lands on its native phase, every
// one-shot event on its effect, and the Settings row reads the right trailing value. The observer
// itself needs a Koin-built ViewModel, so these pin the pure statics its `apply` delegates to.

// MARK: - Phase mapping

@Suite("Hardcover phase mapping")
struct HardcoverPhaseMappingTests {
    @Test func loadingAndNotOfferedMapDirectly() {
        #expect(HardcoverSettingsObserver.phase(from: HardcoverSettingsUiStateLoading.shared) == .loading)
        #expect(HardcoverSettingsObserver.phase(from: HardcoverSettingsUiStateNotOffered.shared) == .notOffered)
    }

    @Test func notConnectedWithoutAFailureCarriesOnlyTheBusyFlag() {
        let state = HardcoverSettingsUiStateNotConnected(lastFailure: nil, isStarting: true)
        #expect(HardcoverSettingsObserver.phase(from: state) == .notConnected(failureMessage: nil, isStarting: true))
    }

    @Test func notConnectedExplainsEachFailure() {
        let cases: [(HardcoverLinkFailure, String)] = [
            (.denied, String(localized: "hardcover.failure_denied")),
            (.expired, String(localized: "hardcover.failure_expired")),
            (.unreachable, String(localized: "hardcover.failure_unreachable"))
        ]
        for (failure, message) in cases {
            let state = HardcoverSettingsUiStateNotConnected(lastFailure: failure, isStarting: false)
            let expected = HardcoverPhase.notConnected(failureMessage: message, isStarting: false)
            #expect(HardcoverSettingsObserver.phase(from: state) == expected)
        }
    }

    @Test func linkingCarriesTheCodeTheBareAddressAndThePrefilledPage() {
        let state = HardcoverSettingsUiStateLinking(
            userCode: "ABCD-1234",
            verificationUri: "https://hardcover.app/link",
            verificationUriComplete: "https://hardcover.app/link?code=ABCD-1234",
            expiresAt: 1_790_000_000_000
        )
        guard case .linking(let model) = HardcoverSettingsObserver.phase(from: state) else {
            Issue.record("expected .linking")
            return
        }
        #expect(model.userCode == "ABCD-1234")
        #expect(model.address == "hardcover.app/link")
        #expect(model.pageURL == URL(string: "https://hardcover.app/link?code=ABCD-1234"))
    }

    @Test func connectedCarriesUsernameSinceAndTheBusyFlag() {
        let state = HardcoverSettingsUiStateConnected(
            username: "simon",
            since: 1_790_000_000_000,
            isDisconnecting: true
        )
        guard case .connected(let model) = HardcoverSettingsObserver.phase(from: state) else {
            Issue.record("expected .connected")
            return
        }
        #expect(model.username == "simon")
        #expect(model.since == Date(timeIntervalSince1970: 1_790_000_000))
        #expect(model.isDisconnecting == true)
    }

    @Test func brokenExplainsEachReasonAndRemembersWhoItWas() {
        let cases: [(HardcoverBrokenReason, String)] = [
            (.revoked, String(localized: "hardcover.broken_revoked")),
            (.cannotDecrypt, String(localized: "hardcover.broken_cannot_decrypt")),
            (.missingScope, String(localized: "hardcover.broken_missing_scope"))
        ]
        for (reason, message) in cases {
            let state = HardcoverSettingsUiStateBroken(reason: reason, username: "simon", isStarting: false)
            let expected = HardcoverBrokenModel(reasonMessage: message, username: "simon", isStarting: false)
            #expect(HardcoverSettingsObserver.phase(from: state) == .broken(expected))
        }
    }

    @Test func brokenWithoutAKnownUsernameKeepsItNil() {
        let state = HardcoverSettingsUiStateBroken(reason: .revoked, username: nil, isStarting: true)
        guard case .broken(let model) = HardcoverSettingsObserver.phase(from: state) else {
            Issue.record("expected .broken")
            return
        }
        #expect(model.username == nil)
        #expect(model.isStarting == true)
    }
}

// MARK: - Event routing

@Suite("Hardcover event routing")
struct HardcoverEventRoutingTests {
    @Test func openVerificationPageOpensTheURL() {
        let event = HardcoverSettingsEventOpenVerificationPage(url: "https://hardcover.app/link?code=ABCD-1234")
        #expect(HardcoverSettingsObserver.effect(of: event) == .open(URL(string: "https://hardcover.app/link?code=ABCD-1234")!))
    }

    @Test func showErrorBecomesAnAlertWithTheErrorsMessage() {
        let error = ServerConnectErrorInvalidUrl(correlationId: nil, debugInfo: nil, reason: "bad url")
        let event = HardcoverSettingsEventShowError(error: error)
        #expect(HardcoverSettingsObserver.effect(of: event) == .alert(error.message))
    }
}

// MARK: - Connected announcement

@Suite("Hardcover connected announcement")
struct HardcoverAnnouncementTests {
    private let linking = HardcoverPhase.linking(
        HardcoverLinkingModel(userCode: "ABCD-1234", address: "hardcover.app/link", pageURL: nil)
    )
    private let connected = HardcoverPhase.connected(
        HardcoverConnectedModel(username: "simon", since: Date(timeIntervalSince1970: 0), isDisconnecting: false)
    )

    @Test func approvingWhileWaitingIsAnnounced() {
        let expected = String(format: String(localized: "hardcover.row_subtitle_connected"), "simon")
        #expect(HardcoverSettingsObserver.announcement(from: linking, to: connected) == expected)
    }

    @Test func openingTheScreenAlreadyConnectedIsNotAnnounced() {
        #expect(HardcoverSettingsObserver.announcement(from: .loading, to: connected) == nil)
    }

    @Test func aBusyFlagFlippingWhileConnectedIsNotAnnounced() {
        let disconnecting = HardcoverPhase.connected(
            HardcoverConnectedModel(username: "simon", since: Date(timeIntervalSince1970: 0), isDisconnecting: true)
        )
        #expect(HardcoverSettingsObserver.announcement(from: connected, to: disconnecting) == nil)
    }
}

// MARK: - Settings row value

@Suite("Hardcover settings row")
struct HardcoverRowValueTests {
    @Test func hiddenWhenTheServerDoesNotOfferHardcover() {
        #expect(HardcoverRowValue(from: nil) == nil)
    }

    @Test func notConnectedHasNoTrailingValue() {
        let row = HardcoverRowValue(from: HardcoverRowStateNotConnected.shared)
        #expect(row == .notConnected)
        #expect(row?.trailingText == nil)
    }

    @Test func connectedShowsTheUsername() {
        let row = HardcoverRowValue(from: HardcoverRowStateConnected(username: "simon"))
        #expect(row == .connected(username: "simon"))
        #expect(row?.trailingText == "simon")
    }

    @Test func connectingShowsWaiting() {
        let row = HardcoverRowValue(from: HardcoverRowStateConnecting.shared)
        #expect(row == .connecting)
        #expect(row?.trailingText == String(localized: "hardcover.row_value_connecting"))
    }

    @Test func needsAttentionAsksForAReconnect() {
        let row = HardcoverRowValue(from: HardcoverRowStateNeedsAttention.shared)
        #expect(row == .needsAttention)
        #expect(row?.trailingText == String(localized: "hardcover.reconnect"))
    }
}
