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
        #expect(
            phone.blocks(slot: .empty, notice: .clear, hasAlsoInProgress: true, isArriving: false)
                == [.alsoInProgress, .stats, .shelves]
        )
        #expect(
            phone.blocks(slot: .empty, notice: .clear, hasAlsoInProgress: false, isArriving: false)
                == [.stats, .shelves]
        )
    }

    @Test func nothingLoadedLeadsWithResume() {
        #expect(
            HomeLayout.forWidth(390).blocks(slot: .resume, notice: .clear, hasAlsoInProgress: true, isArriving: false)
                == [.resume, .alsoInProgress, .stats, .shelves]
        )
        #expect(
            HomeLayout.forWidth(1032).blocks(slot: .resume, notice: .clear, hasAlsoInProgress: true, isArriving: false)
                == [.slotBesideStats, .alsoInProgress, .shelves]
        )
    }

    @Test func aLoadedBookOnAnIPadPutsStatsBesideShelves() {
        #expect(
            HomeLayout.forWidth(1032).blocks(slot: .empty, notice: .clear, hasAlsoInProgress: true, isArriving: false)
                == [.alsoInProgress, .statsBesideShelves]
        )
    }

    @Test func everySlotLeadsAndGoesBesideTheStatsWhenWide() {
        let narrow = HomeLayout.forWidth(390)
        let wide = HomeLayout.forWidth(1024)
        #expect(
            narrow.blocks(slot: .finished, notice: .clear, hasAlsoInProgress: true, isArriving: false)
                == [.finished, .alsoInProgress, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .nothingInProgress, notice: .clear, hasAlsoInProgress: false, isArriving: false)
                == [.nothingInProgress, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .welcome, notice: .clear, hasAlsoInProgress: false, isArriving: false)
                == [.welcome, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .firstSync, notice: .clear, hasAlsoInProgress: false, isArriving: true)
                == [.firstSyncStrip, .firstSync, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .empty, notice: .offline, hasAlsoInProgress: true, isArriving: false)
                == [.offline, .alsoInProgress, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .empty, notice: .continueListeningFailed, hasAlsoInProgress: false, isArriving: false)
                == [.continueListeningFailed, .stats, .shelves]
        )
        for slot in [HomeSlotShape.finished, .nothingInProgress, .welcome, .firstSync, .resume] {
            let first = wide.blocks(slot: slot, notice: .clear, hasAlsoInProgress: true, isArriving: false).first
            #expect(first == .slotBesideStats)
        }
    }

    @Test func theSlotKindsMapToTheirShapes() {
        #expect(HomeSlotItem.absent.shape == .empty)
        #expect(HomeSlotItem.firstSync.shape == .firstSync)
        #expect(HomeSlotItem.nothingInProgress(nil).shape == .nothingInProgress)
        #expect(HomeSlotItem.newListener(covers: []).shape == .welcome)
    }
}
