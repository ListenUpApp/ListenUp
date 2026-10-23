import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// The player's sheet chrome, read the way VoiceOver and large text meet it (player spec §3.8, §8): a close button
/// with a full target, and content that scrolls instead of clipping at accessibility sizes (HIG, Typography).
@MainActor
@Suite("Player sheet chrome accessibility", .serialized)
struct PlayerSheetsAccessibilityTests {
    private var forty: some View {
        PlayerSheetChrome(title: "Forty Lines") {
            VStack(alignment: .leading) {
                ForEach(1...40, id: \.self) { Text("Line \($0)") }
            }
        }
    }

    @Test func atAccessibilitySizesTheContentScrollsInsteadOfClipping() async {
        let hosted = await HostedView(forty, size: CGSize(width: 393, height: 430), dynamicTypeSize: .accessibility3)
        defer { hosted.close() }
        #expect(hosted.scrollsVertically, "\(hosted.tree)")
    }

    @Test func theCloseButtonIsAFullTarget() async throws {
        let hosted = await HostedView(forty, size: CGSize(width: 393, height: 430))
        defer { hosted.close() }
        let close = try #require(hosted.stop(labelled: "Close"), "\(hosted.tree)")
        #expect(close.isButton)
        #expect(close.frame.width >= 44 && close.frame.height >= 44, "\(close.frame)")
    }

    @Test func aMediumDetentGrowsALargeOneAtAccessibilitySizes() {
        #expect(PlayerSheetDetents.detents(for: .large) == [.medium])
        #expect(PlayerSheetDetents.detents(for: .xxxLarge) == [.medium])
        #expect(PlayerSheetDetents.detents(for: .accessibility1) == [.medium, .large])
    }
}
