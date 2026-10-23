import SwiftUI
import Testing
import UIKit
@testable import ListenUp

@Suite("The Speed sheet's rules")
struct SpeedSheetModelTests {
    @Test("thirty presses of Slower from 2.5× land on exactly 1×, with no float drift")
    func slowerThirtyTimes() {
        var speed: Float = 2.5
        for _ in 0..<30 { speed = SpeedSheetModel.step(speed, up: false) }
        #expect(speed == 1.0)
    }

    @Test("the steps stop at the ends of the range")
    func clamped() {
        #expect(SpeedSheetModel.step(3.0, up: true) == 3.0)
        #expect(SpeedSheetModel.step(0.5, up: false) == 0.5)
    }

    @Test("a preset is selected only at its own speed")
    func selection() {
        #expect(SpeedSheetModel.isSelected(1.25, speed: 1.25))
        #expect(SpeedSheetModel.isSelected(1.5, speed: 1.4999999))
        #expect(!SpeedSheetModel.isSelected(1.5, speed: 1.25))
        #expect(!SpeedSheetModel.isSelected(1.25, speed: 1.3))
    }

    @Test("reset shows only away from the default")
    func reset() {
        #expect(SpeedSheetModel.showsReset(speed: 1.25, defaultSpeed: 1.0))
        #expect(!SpeedSheetModel.showsReset(speed: 1.0, defaultSpeed: 1.0))
    }

    @Test("VoiceOver hears the speed as a number of times")
    func spoken() {
        #expect(SpeedSheetModel.spokenValue(1.25) == "1.25 times")
    }
}

@MainActor
@Suite("The Speed sheet, read by VoiceOver", .serialized)
struct SpeedSheetAccessibilityTests {
    private final class Calls {
        var speeds: [Float] = []
        var steps: [Bool] = []
        var resets = 0
    }

    private func host(
        speed: Float, calls: Calls = Calls(), size: CGSize = CGSize(width: 393, height: 852),
        dynamicTypeSize: DynamicTypeSize = .large
    ) async -> HostedView {
        let actions = SpeedSheetActions(
            setSpeed: { calls.speeds.append($0) }, step: { calls.steps.append($0) }, reset: { calls.resets += 1 })
        return await HostedView(
            PlayerSheetChrome(title: String(localized: "player.sheet_speed_title_ios")) {
                SpeedSheetContent(speed: speed, defaultSpeed: 1.0, leftAtSpeedMs: 116_700_000, actions: actions)
            },
            size: size, dynamicTypeSize: dynamicTypeSize)
    }

    @Test func theSliderIsAdjustableAndSpeaksTimes() async throws {
        let hosted = await host(speed: 1.25)
        defer { hosted.close() }
        // The navigation title shares the name; the slider is the adjustable one.
        let slider = try #require(
            hosted.stops(labelled: String(localized: "player.sheet_speed_title_ios"))
                .first { $0.traits.contains(.adjustable) },
            "\(hosted.tree)")
        #expect(slider.value == "1.25 times")
    }

    @Test func slowerAndFasterAreFullTargetsThatStep() async throws {
        let calls = Calls()
        let hosted = await host(speed: 1.25, calls: calls)
        defer { hosted.close() }
        for key in ["player.sheet_slower", "player.sheet_faster"] {
            let stop = try #require(hosted.stop(labelled: String(localized: String.LocalizationValue(key))),
                                    "\(hosted.tree)")
            // 44pt, give or take the layout's sub-point rounding.
            #expect(stop.frame.width >= 43.9 && stop.frame.height >= 43.9, "\(key): \(stop.frame)")
        }
        #expect(hosted.activate(labelled: String(localized: "player.sheet_faster")))
        #expect(calls.steps == [true])
    }

    @Test func theCurrentPresetIsSelectedAndOffPresetSpeedsSelectNone() async throws {
        let hosted = await host(speed: 1.25)
        let current = try #require(hosted.stop(labelled: "1.25×"), "\(hosted.tree)")
        #expect(current.isSelected)
        #expect(hosted.stop(labelled: "1.5×")?.isSelected == false)
        hosted.close()

        let between = await host(speed: 1.3)
        defer { between.close() }
        #expect(between.stops.filter { $0.label.hasSuffix("×") && $0.isSelected }.isEmpty, "\(between.tree)")
    }

    @Test func resetAppearsAwayFromTheDefault() async throws {
        let reset = String(format: String(localized: "player.sheet_reset_speed_ios"), "1×")
        let calls = Calls()
        let away = await host(speed: 1.25, calls: calls)
        #expect(away.stop(labelled: reset) != nil, "\(away.tree)")
        #expect(away.activate(labelled: reset))
        #expect(calls.resets == 1)
        away.close()
        let home = await host(speed: 1.0)
        defer { home.close() }
        #expect(home.stop(labelled: reset) == nil)
    }

    @Test func atAccessibilitySizesItScrollsAndEveryPresetIsAFullTarget() async throws {
        let hosted = await host(
            speed: 1.25, size: CGSize(width: 393, height: 430), dynamicTypeSize: .accessibility3)
        defer { hosted.close() }
        #expect(hosted.scrollsVertically)
        for label in PlaybackRates.catalogue.map(PlaybackRates.format) {
            // Below the fold a preset is still in the tree; its frame is what matters.
            let stop = try #require(hosted.stop(labelled: label), "\(label) missing:\n\(hosted.tree)")
            #expect(stop.frame.height >= 43.9, "\(label): \(stop.frame)")
        }
    }
}
