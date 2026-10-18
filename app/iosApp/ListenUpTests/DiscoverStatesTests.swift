import Foundation
import Testing
import Shared
@testable import ListenUp

/// Spec §3.8 on iOS: each Discover section's state is a pure decision, tested off the view.
@Suite("Discover section states")
struct DiscoverStatesTests {
    private let admin = DiscoverViewerRow(userId: "me", displayName: "Simon", canInvite: true)
    private let member = DiscoverViewerRow(userId: "me", displayName: "Simon", canInvite: false)

    @Test func anEmptyRosterBeforeSyncIsASkeleton() {
        #expect(ListeningNowSection.treatment(for: .ready([]), community: .unknown, viewer: nil) == .skeleton)
    }

    @Test func loadingIsASkeleton() {
        #expect(ListeningNowSection.treatment(for: .loading, community: .others, viewer: nil) == .skeleton)
    }

    @Test func onlyYouIsAnInvitationAndOnlyAnAdminGetsTheButton() {
        let asAdmin = ListeningNowSection.treatment(for: .ready([]), community: .justYou, viewer: admin)
        let asMember = ListeningNowSection.treatment(for: .ready([]), community: .justYou, viewer: member)
        #expect(asAdmin == .invitation(canInvite: true))
        #expect(asMember == .invitation(canInvite: false))
    }

    @Test func othersButNobodyListeningSaysSo() {
        #expect(ListeningNowSection.treatment(for: .ready([]), community: .others, viewer: nil) == .nobodyListening)
    }

    @Test func aFailureIsALineAndTryAgain() {
        #expect(ListeningNowSection.treatment(for: .error, community: .others, viewer: nil) == .retry)
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

    @Test func onlyYouHidesTheCategoryAndClosesWithJustYou() {
        #expect(LeaderboardSectionView.showsCategory(community: .justYou) == false)
        #expect(LeaderboardSectionView.showsCategory(community: .others) == true)
        let justYou = "Just you so far. The board fills in as people join."
        #expect(LeaderboardSectionView.footer(community: .justYou) == justYou)
        #expect(LeaderboardSectionView.footer(community: .others) == nil)
    }

    @Test func anEmptyPeriodWithOthersSaysNobodyListened() {
        #expect(LeaderboardSectionView.emptyLine(community: .others) == "Nobody listened in this period yet.")
        let justYou = "Just you so far. The board fills in as people join."
        #expect(LeaderboardSectionView.emptyLine(community: .justYou) == justYou)
        #expect(LeaderboardSectionView.emptyLine(community: .unknown) == nil)
    }

    @Test func allStartedIsItsOwnPhase() {
        #expect(DiscoverObserver.booksPhase(from: DiscoverBooksUiStateAllStarted.shared) == .allStarted)
    }

    @Test func theLeaderboardsLoadingRowsAreItsRealRows() {
        #expect(LeaderRow.rowHeight == 54)
        #expect(LeaderRow.avatarSize == 38)
        #expect(LeaderRow.rankSize == 26)
    }
}
