import SwiftUI
import Testing
import UIKit
@preconcurrency import Shared
@testable import ListenUp
@Suite("CoverThemeMotion")
struct CoverThemeMotionTests {
    @Test func onlyColoursThatArrivedLateCrossfade() {
        #expect(CoverThemeMotion.animation(fadesIn: false, reduceMotion: false) == nil)
        #expect(CoverThemeMotion.animation(fadesIn: true, reduceMotion: false) != nil)
    }

    @Test func reduceMotionNeverAnimates() {
        #expect(CoverThemeMotion.animation(fadesIn: true, reduceMotion: true) == nil)
    }
}
