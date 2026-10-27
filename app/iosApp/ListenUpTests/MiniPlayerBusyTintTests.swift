import SwiftUI
import Testing
@testable import ListenUp

/// Buffering keeps the play control's look (Simon, 2026-10-27): the spinner takes the glyph's own colour, so the mini
/// player's grey spinner turns coral and Now Playing's stays the tint. Tint is not in the accessibility tree, so the
/// rule is pinned structurally: the busy tint IS the glyph's.
@MainActor
@Suite("Busy takes the glyph's colour")
struct MiniPlayerBusyTintTests {
    @Test func theMiniPlayersSpinnerIsTheGlyphsCoral() {
        #expect(MiniPlayerPrimaryButton.busyTint == MiniPlayerPrimaryButton.glyphTint)
        #expect(MiniPlayerPrimaryButton.glyphTint == Color.listenUpOrange)
    }

    @Test func nowPlayingsSpinnerIsThePlayGlyphsTint() {
        #expect(PlayerTransportControls.busyTint == PlayerTransportControls.playGlyphTint)
    }
}
