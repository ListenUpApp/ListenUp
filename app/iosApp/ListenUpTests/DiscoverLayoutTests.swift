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

    /// Both leaderboard pickers turn into menus at accessibility text sizes, whatever the width (Q3).
    @Test func leaderboardPickersAreMenusAtAccessibilitySizes() {
        #expect(LeaderboardPickerForm(.large) == .segmented)
        #expect(LeaderboardPickerForm(.xxxLarge) == .segmented)
        #expect(LeaderboardPickerForm(.accessibility1) == .menu)
        #expect(LeaderboardPickerForm(.accessibility5) == .menu)
    }

    /// The iPad grids carry the board's column counts: five, five, and six for recently added (§3.7).
    @Test func regularGridsUseTheBoardsColumnCounts() {
        #expect(ListeningNowSection.regularColumnCount == 5)
        #expect(SomethingNewSection.regularColumnCount == 5)
        #expect(RecentlyAddedSection.regularColumnCount == 6)
        typealias Grid = DiscoverCardGrid<DiscoverBook, EmptyView>
        #expect(Grid.regularColumns(count: 5, spacing: 18).count == 5)
        #expect(Grid.regularColumns(count: 6, spacing: 16).count == 6)
    }

    /// The Leaderboard and Activity share an iPad row about 1 : 1.15, after the gap (§3.7).
    @Test func thePairedRowSplitsOneToOnePointOneFive() {
        #expect(DiscoverPairedColumns.boardWeights == [1, 1.15])
        let widths = DiscoverPairedColumns.columnWidths(
            total: 1_000, spacing: 22, weights: DiscoverPairedColumns.boardWeights
        )
        #expect(widths.count == 2)
        #expect(abs(widths.reduce(0, +) - 978) < 0.001)
        #expect(abs(widths[1] / widths[0] - 1.15) < 0.001)
    }

    @Test func aPairedRowNarrowerThanItsGapGivesNoWidth() {
        #expect(DiscoverPairedColumns.columnWidths(total: 10, spacing: 22, weights: [1, 1.15]) == [0, 0])
    }
}
