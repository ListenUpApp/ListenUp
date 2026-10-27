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
            phone.blocks(slot: .empty, everythingInProgress: true)
                == [.everythingInProgress, .stats, .shelves]
        )
        #expect(
            phone.blocks(slot: .empty, everythingInProgress: false)
                == [.stats, .shelves]
        )
    }

    @Test func booksInProgressWithNothingLoadedLeadWithEverythingInProgressNotAHero() {
        // Home has no Resume hero (Simon, 2026-10-27): the slot is empty and the list leads.
        #expect(
            HomeLayout.forWidth(390).blocks(slot: .empty, everythingInProgress: true)
                == [.everythingInProgress, .stats, .shelves]
        )
        #expect(
            HomeLayout.forWidth(1032).blocks(slot: .finished, everythingInProgress: true)
                == [.slotBesideStats, .everythingInProgress, .shelves]
        )
    }

    @Test func aLoadedBookOnAnIPadPutsStatsBesideShelves() {
        #expect(
            HomeLayout.forWidth(1032).blocks(slot: .empty, everythingInProgress: true)
                == [.everythingInProgress, .statsBesideShelves]
        )
    }

    @Test func everySlotLeadsAndGoesBesideTheStatsWhenWide() {
        let narrow = HomeLayout.forWidth(390)
        let wide = HomeLayout.forWidth(1024)
        #expect(
            narrow.blocks(slot: .finished, everythingInProgress: true)
                == [.finished, .everythingInProgress, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .nothingInProgress, everythingInProgress: false)
                == [.nothingInProgress, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .welcome, everythingInProgress: false)
                == [.welcome, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .firstSync, everythingInProgress: false, strip: true)
                == [.firstSyncStrip, .firstSync, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .empty, everythingInProgress: true, offline: true)
                == [.offline, .everythingInProgress, .stats, .shelves]
        )
        #expect(
            narrow.blocks(slot: .empty, everythingInProgress: false, failed: true)
                == [.continueListeningFailed, .stats, .shelves]
        )
        for slot in [HomeSlotShape.finished, .nothingInProgress, .welcome, .firstSync] {
            let first = wide.blocks(slot: slot, everythingInProgress: true).first
            #expect(first == .slotBesideStats)
        }
    }

    @Test func theSlotKindsMapToTheirShapes() {
        #expect(HomeSlotItem.absent.shape == .empty)
        #expect(HomeSlotItem.firstSync.shape == .firstSync)
        #expect(HomeSlotItem.nothingInProgress(nil).shape == .nothingInProgress)
        #expect(HomeSlotItem.newListener(covers: []).shape == .welcome)
    }

    @Test func offlineAndAFailedRowShowTogetherAndTheStripSitsAboveAnySlot() {
        let phone = HomeLayout.forWidth(390)
        #expect(
            phone.blocks(slot: .empty, everythingInProgress: false, offline: true, failed: true)
                == [.offline, .continueListeningFailed, .stats, .shelves]
        )
        #expect(
            phone.blocks(slot: .finished, everythingInProgress: false, offline: true, strip: true)
                == [.offline, .firstSyncStrip, .finished, .stats, .shelves]
        )
    }
}

private extension HomeLayout {
    /// The block order with every signal off unless a test turns it on.
    func blocks(
        slot: HomeSlotShape,
        everythingInProgress: Bool,
        offline: Bool = false,
        failed: Bool = false,
        strip: Bool = false
    ) -> [HomeBlock] {
        blocks(
            slot: slot,
            isOffline: offline,
            continueListeningFailed: failed,
            showsStrip: strip,
            hasEverythingInProgress: everythingInProgress
        )
    }
}
