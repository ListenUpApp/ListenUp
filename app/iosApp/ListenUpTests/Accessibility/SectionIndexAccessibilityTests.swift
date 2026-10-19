import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// The Library's section index read the way VoiceOver meets it (spec §5.2): one adjustable control
/// named "Section index" whose value says where a swipe will jump.
@MainActor
@Suite("Section index accessibility", .serialized)
struct SectionIndexAccessibilityTests {
    @Test func theIndexIsOneAdjustableStopWhoseValueNamesTheJump() async throws {
        let hosted = await HostedView(
            SectionIndexBar(letters: ["A", "M", "Z"], onLetterSelected: { _ in }, isVisible: true),
            size: CGSize(width: 60, height: 400)
        )
        defer { hosted.close() }
        let index = try #require(hosted.stop(labelled: String(localized: "library.section_index")), "\(hosted.tree)")
        #expect(index.traits.contains(.adjustable))
        #expect(index.value == String(format: String(localized: "library.index_jump"), "A"))
    }
}
