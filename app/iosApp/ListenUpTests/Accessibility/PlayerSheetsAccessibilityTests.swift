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

    /// The system's close button (`Button(role: .close)`), in the sheet's top bar. Its glass disc is 36pt; the bar gives
    /// it the rest of its 44pt target, as it does every bar button (HIG, Sheets; HIG, Toolbars), so the test pins that it
    /// is the system's, where the system sizes it, rather than a frame the system owns.
    @Test func theCloseButtonIsTheSystemsInTheTopBar() async throws {
        let hosted = await HostedView(forty, size: CGSize(width: 393, height: 430))
        defer { hosted.close() }
        let close = try #require(hosted.stop(labelled: "Close"), "\(hosted.tree)")
        #expect(close.isButton)
        #expect(close.frame.minY < 120 && close.frame.maxX > 300, "\(close.frame)")
        #expect(close.frame.width >= 36 && close.frame.height >= 36, "\(close.frame)")
    }

    @Test func aMediumDetentGrowsALargeOneAtAccessibilitySizes() {
        #expect(PlayerSheetDetents.detents(for: .large) == [.medium])
        #expect(PlayerSheetDetents.detents(for: .xxxLarge) == [.medium])
        #expect(PlayerSheetDetents.detents(for: .accessibility1) == [.medium, .large])
    }
}
