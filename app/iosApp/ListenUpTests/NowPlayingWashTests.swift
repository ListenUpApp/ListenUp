import SwiftUI
import Testing
import UIKit
@testable import ListenUp

@Suite("Now Playing's wash: the cover's, or the player's neutral")
@MainActor
struct NowPlayingWashTests {
    @Test("no cover colours: the player's neutral, in light and in dark")
    func neutral() {
        #expect(FullScreenPlayerView.wash(nil, scheme: .light) == PlayerWashTone.color(for: .light))
        #expect(FullScreenPlayerView.wash(nil, scheme: .dark) == PlayerWashTone.color(for: .dark))
    }

    @Test("the light neutral is the spec's #F5EFE9, and the dark one the system's secondary background")
    func decidedValues() {
        let lightTraits = UITraitCollection(userInterfaceStyle: .light)
        let light = SRGBColor(UIColor(PlayerWashTone.color(for: .light)), resolvedFor: lightTraits)
        let channels = [light.red, light.green, light.blue].map { Int(($0 * 255).rounded()) }
        #expect(channels == [0xF5, 0xEF, 0xE9])
        let dark = UITraitCollection(userInterfaceStyle: .dark)
        let resolved = SRGBColor(UIColor(PlayerWashTone.color(for: .dark)), resolvedFor: dark)
        let system = SRGBColor(.secondarySystemBackground, resolvedFor: dark)
        #expect(abs(resolved.red - system.red) < 0.002 && abs(resolved.blue - system.blue) < 0.002)
    }

    @Test("label ink on the light neutral passes 4.5:1")
    func contrast() {
        let traits = UITraitCollection(userInterfaceStyle: .light)
        let wash = SRGBColor(UIColor(PlayerWashTone.color(for: .light)), resolvedFor: traits)
        #expect(SRGBColor(.label, resolvedFor: traits).contrastRatio(against: wash) >= ContrastMinimum.text)
    }
}
