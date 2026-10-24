import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// Volume Boost as a segmented choice that stays open (player spec §5.5.3, PB1–PB5; HIG, Segmented controls).
@MainActor
@Suite("The Volume Boost sheet", .serialized)
struct BoostSheetTests {
    private final class Calls {
        var picked: [Float] = []
        var resets = 0
    }

    private func host(current: Float, defaultDb: Float? = 0, calls: Calls = Calls()) async -> HostedView {
        await HostedView(
            BoostPickerSheet(
                currentBoostDb: current, defaultBoostDb: defaultDb,
                onBoostSelected: { calls.picked.append($0) }, onUseDefault: { calls.resets += 1 }),
            size: CGSize(width: 393, height: 852))
    }

    @Test func fiveSegmentsWithTheCurrentOneSelectedEachAFullTarget() async throws {
        let hosted = await host(current: 6)
        defer { hosted.close() }
        for label in ["Off", "+3", "+6", "+9", "+12"] {
            let segment = try #require(hosted.stop(labelled: label), "\(label) missing:\n\(hosted.tree)")
            #expect(segment.frame.height >= 44, "\(label): \(segment.frame)")
            #expect(segment.isSelected == (label == "+6"), "\(label)")
        }
    }

    @Test func theNoteAndTheResetToTheDefault() async throws {
        let calls = Calls()
        let hosted = await host(current: 6, calls: calls)
        defer { hosted.close() }
        #expect(!hosted.stops(labelContaining: String(localized: "player.sheet_boost_note")).isEmpty, "\(hosted.tree)")
        let reset = String(format: String(localized: "player.sheet_reset_boost_ios"), "Off")
        #expect(hosted.activate(labelled: reset), "\(hosted.tree)")
        #expect(calls.resets == 1)
    }

    @Test func atTheDefaultThereIsNothingToReset() async {
        let hosted = await host(current: 0)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: String(format: String(localized: "player.sheet_reset_boost_ios"), "Off")) == nil)
    }

    @Test func pickingALevelSetsItAndTheSheetStaysUp() async throws {
        let calls = Calls()
        let hosted = await host(current: 6, calls: calls)
        defer { hosted.close() }
        #expect(hosted.activate(labelled: "+9"), "\(hosted.tree)")
        await hosted.settle()
        #expect(calls.picked == [9])
        // Nothing in the sheet dismisses on a pick: its segments are still there to try another level.
        #expect(hosted.stop(labelled: "+12") != nil)
    }
}
