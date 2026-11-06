import CoreGraphics
import Shared
import Testing
@testable import ListenUp

/// Home runs the width it is given (iosApp rule 12) and keeps one section order (spec §2.1, §3.4).
@Suite("Home layout")
struct HomeLayoutTests {
    @Test func phonesAndNarrowSplitViewsAreOneColumnWithAScrollingRail() {
        for width: CGFloat in [375, 390, 440, 683] {
            let layout = HomeLayout.forWidth(width)
            #expect(layout.arrangement == .column, "width \(width)")
            #expect(layout.rail == .scrolling(cardWidth: 150), "width \(width)")
        }
    }

    @Test func anIPadWithItsSidebarIsWideWithStatsFirst() {
        // 1180pt iPad, 320pt sidebar: ~860pt of content (board Home-iPad).
        #expect(HomeLayout.forWidth(860).arrangement == .wide(statsWidth: 380))
        #expect(HomeLayout.forWidth(720).arrangement == .wide(statsWidth: 380))
    }

    @Test func theWideRailIsOneRowWhoseColumnsFlowWithTheWidth() {
        #expect(HomeLayout.forWidth(834).rail == .row(columns: 4))
        #expect(HomeLayout.forWidth(1032).rail == .row(columns: 5))
        #expect(HomeLayout.forWidth(1376).rail == .row(columns: 5))
    }

    @Test func marginsWidenOnceThereIsRoom() {
        #expect(HomeLayout.forWidth(390).margin == 20)
        #expect(HomeLayout.forWidth(1032).margin == 32)
    }

    // MARK: - Section order: the shared rule, mapped (built from real Kotlin slots, never Swift ones)

    private func order(
        _ slot: HomeSlot,
        everythingInProgress: Bool,
        isWide: Bool = false,
        offline: Bool = false,
        failed: Bool = false,
        strip: Bool = false
    ) -> [ListenUp.HomeBlock] {
        ListenUp.HomeBlock.order(
            slot: slot,
            isOffline: offline,
            continueListeningFailed: failed,
            showsStrip: strip,
            hasEverythingInProgress: everythingInProgress,
            isWide: isWide
        )
    }

    @Test func theSharedOrderMapsToTheNativeBlocks() {
        #expect(order(HomeSlotAbsent.shared, everythingInProgress: true) == [.everythingInProgress, .stats, .shelves])
        #expect(order(HomeSlotAbsent.shared, everythingInProgress: false) == [.stats, .shelves])
        #expect(
            order(HomeSlotAbsent.shared, everythingInProgress: true, isWide: true)
                == [.everythingInProgress, .statsBesideShelves]
        )
        #expect(
            order(HomeSlotFirstSync.shared, everythingInProgress: false, strip: true)
                == [.firstSyncStrip, .firstSync, .stats, .shelves]
        )
        #expect(
            order(HomeSlotNothingInProgress(candidate: nil), everythingInProgress: false)
                == [.nothingInProgress, .stats, .shelves]
        )
        #expect(
            order(HomeSlotNewListener(covers: []), everythingInProgress: false)
                == [.welcome, .stats, .shelves]
        )
        #expect(
            order(HomeSlotAbsent.shared, everythingInProgress: false, offline: true, failed: true)
                == [.offline, .continueListeningFailed, .stats, .shelves]
        )
    }

    @Test func aWideWindowPutsEverySlotBesideTheStats() {
        for slot in [HomeSlotFirstSync.shared as HomeSlot, HomeSlotNothingInProgress(candidate: nil)] {
            #expect(order(slot, everythingInProgress: true, isWide: true).first == .slotBesideStats)
        }
    }
}
