import Testing
import UIKit
@testable import ListenUp

@Suite("AvatarPalette")
struct AvatarPaletteTests {
    private let white = SRGBColor(red: 1, green: 1, blue: 1)

    @Test("white initials read at 4.5:1 on every fill, and 7:1 under Increase Contrast")
    func initialsContrast() {
        for hue in AvatarPalette.hueDegrees + [40] {
            for isDark in [false, true] {
                let normal = AvatarPalette.shade(hueDegrees: hue, isDark: isDark, isHighContrast: false)
                let high = AvatarPalette.shade(hueDegrees: hue, isDark: isDark, isHighContrast: true)
                #expect(white.contrastRatio(against: normal) >= ContrastMinimum.text, "hue \(hue) dark \(isDark)")
                #expect(white.contrastRatio(against: high) >= 7, "hue \(hue) dark \(isDark) high contrast")
            }
        }
    }

    @Test("the dynamic fill resolves to each appearance's solved shade")
    func dynamicFill() {
        let color = AvatarPalette.uiColor(hueDegrees: 215)
        for traits in UITraitCollection.colorAppearances {
            let resolved = SRGBColor(color, resolvedFor: traits)
            let expected = AvatarPalette.shade(
                hueDegrees: 215,
                isDark: traits.userInterfaceStyle == .dark,
                isHighContrast: traits.accessibilityContrast == .high
            )
            #expect(abs(resolved.red - expected.red) < 0.002)
            #expect(abs(resolved.green - expected.green) < 0.002)
            #expect(abs(resolved.blue - expected.blue) < 0.002)
        }
    }

    @Test("a key always lands in the same slot, and the slots spread")
    func stableSlots() {
        #expect(AvatarPalette.index(forKey: "user-123") == AvatarPalette.index(forKey: "user-123"))
        let slots = Set((0..<200).map { AvatarPalette.index(forKey: "contributor-\($0)") })
        #expect(slots.count == AvatarPalette.hueDegrees.count)
    }

    @Test("no palette hue sits on the brand coral or the warning amber")
    func huesAvoidBrandAndWarning() {
        for hue in AvatarPalette.hueDegrees {
            for reserved in [12.0, 40.0] {
                let distance = min(abs(hue - reserved), 360 - abs(hue - reserved))
                #expect(distance >= 30, "hue \(hue) is \(distance)° from \(reserved)°")
            }
        }
    }
}
