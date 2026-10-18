import Foundation
import Testing
@testable import ListenUp

/// Spec §3.8 on iOS: each Discover section's state is a pure decision, tested off the view.
@Suite("Discover section states")
struct DiscoverStatesTests {
    private let admin = DiscoverViewerRow(userId: "me", displayName: "Simon", canInvite: true)
    private let member = DiscoverViewerRow(userId: "me", displayName: "Simon", canInvite: false)

    @Test func anEmptyRosterBeforeSyncIsASkeleton() {
        #expect(ListeningNowSection.body(for: .ready([]), community: .unknown, viewer: nil) == .skeleton)
    }

    @Test func loadingIsASkeleton() {
        #expect(ListeningNowSection.body(for: .loading, community: .others, viewer: nil) == .skeleton)
    }

    @Test func onlyYouIsAnInvitationAndOnlyAnAdminGetsTheButton() {
        #expect(ListeningNowSection.body(for: .ready([]), community: .justYou, viewer: admin) == .invitation(canInvite: true))
        #expect(ListeningNowSection.body(for: .ready([]), community: .justYou, viewer: member) == .invitation(canInvite: false))
    }

    @Test func othersButNobodyListeningSaysSo() {
        #expect(ListeningNowSection.body(for: .ready([]), community: .others, viewer: nil) == .nobodyListening)
    }

    @Test func aFailureIsALineAndTryAgain() {
        #expect(ListeningNowSection.body(for: .error, community: .others, viewer: nil) == .retry)
    }

    @Test func shelvesOfflineNeedAConnection() {
        #expect(ShelvesByOthersSection.retryLine(needsConnection: true) == "Shelves by others need a connection")
        #expect(ShelvesByOthersSection.retryLine(needsConnection: false) == "Couldn't load shelves by others")
    }

    @Test func shuffleStepsAsideWhenThereIsNothingToDraw() {
        #expect(SomethingNewSection.showsShuffle(.allStarted) == false)
        #expect(SomethingNewSection.showsShuffle(.error) == false)
        #expect(SomethingNewSection.showsShuffle(.ready([])) == true)
    }
}
