import Testing
@testable import ListenUp

/// Discover reads people first and recently added last, on every width (spec §2.8, §3.7).
@Suite("Discover layout")
struct DiscoverLayoutTests {
    @Test func compactReadsInSpecOrder() {
        #expect(
            DiscoverSection.compactOrder
                == [.listeningNow, .somethingNew, .leaderboard, .activity, .shelvesByOthers, .recentlyAdded]
        )
    }

    @Test func regularPairsTheLeaderboardWithActivity() {
        #expect(
            DiscoverSection.regularRows
                == [[.listeningNow], [.somethingNew], [.leaderboard, .activity], [.shelvesByOthers], [.recentlyAdded]]
        )
    }

    @Test func regularRowsHoldEverySectionOnceInReadingOrder() {
        #expect(DiscoverSection.regularRows.flatMap { $0 } == DiscoverSection.compactOrder)
    }

    /// An activity sentence keeps the row's width at accessibility sizes: the cover moves under it (§2.8 item 4).
    @Test func activityCoverMovesUnderTheSentenceAtAccessibilitySizes() {
        #expect(!ActivityRow.placesCoverBelow(at: .large))
        #expect(!ActivityRow.placesCoverBelow(at: .xxxLarge))
        #expect(ActivityRow.placesCoverBelow(at: .accessibility1))
        #expect(ActivityRow.placesCoverBelow(at: .accessibility5))
    }
}
