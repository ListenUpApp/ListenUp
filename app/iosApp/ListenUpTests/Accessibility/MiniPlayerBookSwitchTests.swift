import SwiftUI
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// A book switch on the mini player reads as deliberate: the old book goes out and the new one comes in, Reduce Motion
/// swaps instantly, and the bar is never empty for a frame (Simon, 2026-10-14).
@MainActor
@Suite("Mini player book switch", .serialized)
struct MiniPlayerBookSwitchTests {
    private static func model(
        _ phase: any PlayerPhase = PlayerPhasePlaying.shared,
        primary: any PrimaryControl = PrimaryControlPause.shared
    ) -> MiniPlayerAccessoryModel {
        MiniPlayerAccessoryModel.make(
            snapshot: PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
                phase: phase, currentIndex: 5, primary: primary, chapterTitle: "Bridge Four")),
            chapterRemainingMs: 511_000, chapterFraction: 0.59, isInline: false, isWide: false)
    }

    private static func otherBook() -> MiniPlayerAccessoryModel {
        var other = model()
        other.bookId = "book-2"
        other.title = "Words of Radiance"
        return other
    }

    @Test("the switch key is the book: a new state of the same book never re-animates")
    func switchKeyFollowsTheBook() {
        let playing = Self.model()
        let paused = Self.model(PlayerPhasePaused.shared, primary: PrimaryControlPlay.shared)
        #expect(playing.switchKey == "book-1")
        #expect(playing.switchKey == paused.switchKey)
        #expect(Self.otherBook().switchKey == "book-2")
    }

    @Test("a switch animates out and in on the system spring; Reduce Motion swaps instantly")
    func motionFollowsReduceMotion() {
        #expect(MiniPlayerBookSwitch.style(reduceMotion: false) == .replace)
        #expect(MiniPlayerBookSwitch.animation(reduceMotion: false) != nil)
        #expect(MiniPlayerBookSwitch.style(reduceMotion: true) == .instant)
        #expect(MiniPlayerBookSwitch.animation(reduceMotion: true) == nil)
    }

    @Test func withReduceMotionABookSwitchIsAnInstantSwap() async throws {
        let holder = SwitchHolder(model: Self.model())
        let hosted = await HostedView(
            SwitchHarness(holder: holder, reduceMotion: true), size: CGSize(width: 361, height: 120))
        defer { hosted.close() }
        holder.model = Self.otherBook()
        await hosted.settle()
        #expect(!hosted.stops(labelContaining: "Words of Radiance").isEmpty, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "The Way of Kings").isEmpty, "\(hosted.tree)")
    }

    @Test func anAnimatedBookSwitchNeverLeavesTheBarEmpty() async throws {
        let holder = SwitchHolder(model: Self.model())
        let hosted = await HostedView(
            SwitchHarness(holder: holder, reduceMotion: false), size: CGSize(width: 361, height: 120))
        defer { hosted.close() }
        holder.model = Self.otherBook()
        // Mid-transition: some book is always on the bar, the outgoing or the incoming one.
        hosted.window.layoutIfNeeded()
        let midway = hosted.stops(labelContaining: "The Way of Kings")
            + hosted.stops(labelContaining: "Words of Radiance")
        #expect(!midway.isEmpty, "\(hosted.tree)")
        // And it lands on the new book.
        try await Task.sleep(for: .milliseconds(900))
        await hosted.settle()
        #expect(!hosted.stops(labelContaining: "Words of Radiance").isEmpty, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "The Way of Kings").isEmpty, "\(hosted.tree)")
    }
}

/// The model a switch test changes under a hosted accessory.
@Observable
@MainActor
final class SwitchHolder {
    var model: MiniPlayerAccessoryModel
    init(model: MiniPlayerAccessoryModel) { self.model = model }
}

/// The accessory inside the book-switch transition, as `MiniPlayerBar` composes it.
private struct SwitchHarness: View {
    let holder: SwitchHolder
    let reduceMotion: Bool
    @Namespace private var namespace

    var body: some View {
        MiniPlayerBookSwitchTransition(key: holder.model.switchKey, reduceMotion: reduceMotion) {
            MiniPlayerAccessoryContent(
                model: holder.model, isInline: false, transitionNamespace: namespace,
                onOpen: {}, onSkipBack: {}, onSkipForward: {}, onPrimary: {})
        }
    }
}
