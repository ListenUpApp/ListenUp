import SwiftUI
import Testing
import UIKit
@preconcurrency import Shared
@testable import ListenUp
/// Kotlin's ARGB `Int` is Swift's `Int32`: opaque colours are negative, so packing goes through the bit pattern.
@Suite("ArgbColor")
struct ArgbColorTests {
    @Test func packsTheBrandCoralAsKotlinWould() {
        let traits = UITraitCollection(userInterfaceStyle: .light)
        #expect(ArgbColor.pack(UIColor(resource: .brandCoral), for: traits) == Int32(bitPattern: 0xFFD7_3812))
    }

    @Test func roundTrips() {
        let argb = Int32(bitPattern: 0xFFE4_E5EB)
        #expect(ArgbColor.pack(ArgbColor.uiColor(argb), for: UITraitCollection(userInterfaceStyle: .light)) == argb)
    }
}
