import SwiftUI
import Testing
import UIKit
@testable import ListenUp

@Suite("CoverTintExtractor.clamp")
struct CoverTintExtractorTests {
    @Test("a near-white raw color is darkened into the legible mid band")
    func nearWhiteDarkens() {
        let tint = CoverTint.clamp(red: 0.97, green: 0.96, blue: 0.95)
        #expect(tint.luminance <= CoverTint.maxLuminance + 0.001)
        #expect(tint.luminance >= CoverTint.minLuminance - 0.001)
    }

    @Test("a near-black raw color is lightened into the legible mid band")
    func nearBlackLightens() {
        let tint = CoverTint.clamp(red: 0.02, green: 0.02, blue: 0.03)
        #expect(tint.luminance >= CoverTint.minLuminance - 0.001)
        #expect(tint.luminance <= CoverTint.maxLuminance + 0.001)
    }

    @Test("a fully desaturated grey is floored to a minimum saturation")
    func greyGetsSaturationFloor() {
        let tint = CoverTint.clamp(red: 0.5, green: 0.5, blue: 0.5)
        #expect(tint.saturation >= CoverTint.minSaturation - 0.001)
    }

    @Test("a vivid color keeps its hue, stays subtle, and lands in the legible band")
    func vividKeepsHue() {
        var expectedHue: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(red: 0.70, green: 0.18, blue: 0.30, alpha: 1).getHue(&expectedHue, saturation: &s, brightness: &b, alpha: &a)
        let tint = CoverTint.clamp(red: 0.70, green: 0.18, blue: 0.30)
        // Subtle: saturation constrained into the band, hue preserved (clamp never shifts hue).
        #expect(tint.saturation <= CoverTint.maxSaturation + 0.001)
        #expect(tint.saturation >= CoverTint.minSaturation - 0.001)
        #expect(abs(tint.hue - Double(expectedHue)) < 0.02)
        // The convergence loop must land a saturated hue in band too, not just near-black.
        #expect(tint.luminance >= CoverTint.minLuminance - 0.001)
        #expect(tint.luminance <= CoverTint.maxLuminance + 0.001)
    }

    @Test("a pure-blue raw color (lowest luminance weight) still converges into the band")
    func pureBlueConverges() {
        let tint = CoverTint.clamp(red: 0.0, green: 0.0, blue: 1.0)
        #expect(tint.luminance >= CoverTint.minLuminance - 0.001)
        #expect(tint.luminance <= CoverTint.maxLuminance + 0.001)
    }
}
