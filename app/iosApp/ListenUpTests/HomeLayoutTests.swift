import CoreGraphics
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

    @Test func aLoadedBookOnAPhoneHasNoSlot() {
        let phone = HomeLayout.forWidth(390)
        #expect(phone.blocks(slot: .empty, hasAlsoInProgress: true) == [.alsoInProgress, .stats, .shelves])
        #expect(phone.blocks(slot: .empty, hasAlsoInProgress: false) == [.stats, .shelves])
    }

    @Test func nothingLoadedLeadsWithResume() {
        #expect(
            HomeLayout.forWidth(390).blocks(slot: .resume, hasAlsoInProgress: true)
                == [.resume, .alsoInProgress, .stats, .shelves]
        )
        #expect(
            HomeLayout.forWidth(1032).blocks(slot: .resume, hasAlsoInProgress: true)
                == [.resumeBesideStats, .alsoInProgress, .shelves]
        )
    }

    @Test func aLoadedBookOnAnIPadPutsStatsBesideShelves() {
        #expect(
            HomeLayout.forWidth(1032).blocks(slot: .empty, hasAlsoInProgress: true)
                == [.alsoInProgress, .statsBesideShelves]
        )
    }

    @Test func untilPR4TheQuietKindsShowTheInterimEmptyState() {
        #expect(
            HomeLayout.forWidth(390).blocks(slot: .nothingToResume, hasAlsoInProgress: false)
                == [.nothingToResume, .stats, .shelves]
        )
    }

    @Test func theSlotKindsMapToTheirShapes() {
        #expect(HomeSlotItem.absent.shape == .empty)
        #expect(HomeSlotItem.firstSync.shape == .empty)
        #expect(HomeSlotItem.nothingInProgress(nil).shape == .nothingToResume)
        #expect(HomeSlotItem.newListener(covers: []).shape == .nothingToResume)
    }
}
