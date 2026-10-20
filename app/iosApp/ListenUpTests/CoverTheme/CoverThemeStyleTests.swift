import SwiftUI
import Testing
import UIKit
@preconcurrency import Shared
@testable import ListenUp
/// The tokens iOS hands the shared rule are the asset catalog's, per appearance (C2).
@Suite("CoverThemeStyle.listenUp")
struct CoverThemeStyleTests {
    @Test func lightAndDarkAreTheAssets() {
        let style = CoverThemeStyle.listenUp
        #expect(style.light.brandArgb == Int32(bitPattern: 0xFFD7_3812))
        #expect(style.light.onBrandArgb == Int32(bitPattern: 0xFFFF_FFFF))
        #expect(style.dark.brandArgb == Int32(bitPattern: 0xFFFF_6A3D))
        #expect(style.dark.onBrandArgb == Int32(bitPattern: 0xFF1C_0A04))
        #expect(style.light.paperArgb == nil)
    }
}
