import SwiftUI
import Testing
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

    @Test("a vivid color keeps its hue and is only gently constrained (subtle)")
    func vividKeepsHue() {
        let tint = CoverTint.clamp(red: 0.70, green: 0.18, blue: 0.30)
        #expect(tint.saturation <= CoverTint.maxSaturation + 0.001)
        #expect(tint.saturation >= CoverTint.minSaturation - 0.001)
    }
}
