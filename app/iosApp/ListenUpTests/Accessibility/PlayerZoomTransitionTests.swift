import SwiftUI
import Testing
import UIKit
@testable import ListenUp
@preconcurrency import Shared

/// The full player still zooms out of the mini player's cover, hosted the way production builds it: a real tab view,
/// the accessory, and the production `PlayerPresentation`.
///
/// From iOS 26.1 the zoom out of a `tabViewBottomAccessory` was reported to fall back to the default slide when the
/// cover's binding is not `@State`-backed (FB20772137; developer.apple.com/forums/thread/804704). The shell's flag is
/// a property of the `@Observable` `MainShellModel`, which is that shape, so both shapes are measured: the
/// production one, and the `@State` one the reports say works.
///
/// The measurement is the presented view's on-screen rect and opacity, read from the presentation layers each frame
/// while the transition runs. The reported fallback is the default cover transition: the full-width page sliding up
/// from below, which moves the presented view itself. The zoom does not move it: on iOS 27.0 (Kit, 2026-10-27) the
/// presented view sat at full size at y = 0 throughout and went from transparent to opaque while the system drew the
/// flight out of the cover elsewhere, for both shapes. So the guard is that the page never slides up.
@MainActor
@Suite("Player zoom transition", .serialized)
struct PlayerZoomTransitionTests {
    static let windowSize = CGSize(width: 393, height: 852)

    static func model() -> MiniPlayerAccessoryModel {
        MiniPlayerAccessoryModel.make(
            snapshot: PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
                phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four")),
            chapterRemainingMs: 511_000, chapterFraction: 0.59, isInline: false, isWide: false)
    }

    @Test func theShellModelsFlagDoesNotFallBackToTheSlide() async throws {
        let shell = MainShellModel()
        let hosted = await HostedView(ObservableFlagStage(shell: shell, model: Self.model()), size: Self.windowSize)
        defer { hosted.close() }
        let flight = try await PlayerFlight.open(in: hosted)
        print("ZOOMPROBE @Observable \(flight)")
        #expect(!flight.slidUp, "\(flight)")
        // With motion on, the arrival is the zoom, never a dissolve; the Reduce Motion check below needs this to hold.
        if !UIAccessibility.isReduceMotionEnabled { #expect(!flight.dissolved, "\(flight)") }
        try await PlayerFlight.close(in: hosted)
    }

    @Test func viewStateDoesNotFallBackToTheSlide() async throws {
        let hosted = await HostedView(ViewStateFlagStage(model: Self.model()), size: Self.windowSize)
        defer { hosted.close() }
        let flight = try await PlayerFlight.open(in: hosted)
        print("ZOOMPROBE @State \(flight)")
        #expect(!flight.slidUp, "\(flight)")
        try await PlayerFlight.close(in: hosted)
    }

    /// Reduce Motion: the system swaps the zoom for a dissolve. Off unless LU_REDUCE_MOTION_CHECK=1 (xcodebuild passes
    /// TEST_RUNNER_LU_REDUCE_MOTION_CHECK through) on a simulator with Reduce Motion switched on, so it never runs in CI.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["LU_REDUCE_MOTION_CHECK"] == "1"))
    func underReduceMotionThePlayerDissolvesIn() async throws {
        // A toggle that did not take fails here, loudly, instead of passing on the zoom.
        try #require(UIAccessibility.isReduceMotionEnabled)
        let shell = MainShellModel()
        let hosted = await HostedView(ObservableFlagStage(shell: shell, model: Self.model()), size: Self.windowSize)
        defer { hosted.close() }
        let flight = try await PlayerFlight.open(in: hosted)
        print("ZOOMPROBE ReduceMotion \(flight)")
        #expect(flight.dissolved, "\(flight)")
        #expect(!flight.slidUp, "\(flight)")
        try await PlayerFlight.close(in: hosted)
    }

    /// When the player closes, VoiceOver focus goes back to the mini player's book, the control that opened it.
    @Test func closingThePlayerHandsFocusBackToTheBook() async throws {
        let probe = FocusProbe()
        let shell = MainShellModel()
        let hosted = await HostedView(
            ObservableFlagStage(shell: shell, model: Self.model(), probe: probe), size: Self.windowSize)
        defer { hosted.close() }
        _ = try await PlayerFlight.open(in: hosted)
        #expect(probe.focusedBook == 0)
        try await PlayerFlight.close(in: hosted)
        try await PlayerFlight.waitUntil { probe.dismissals == 1 }
        try? await PlayerFlight.waitUntil { probe.focusedBook > 0 }
        #expect(probe.dismissals == 1)
        #expect(probe.focusedBook == 1, "the book's focus binding never flipped on dismiss")
    }
}

/// What the presented player's view did while it arrived: its on-screen rect and opacity, sampled each frame.
@MainActor
struct PlayerFlight: CustomStringConvertible {
    struct Sample {
        let elapsedMs: Int
        let frame: CGRect
        let opacity: Float
    }

    let windowWidth: CGFloat
    let samples: [Sample]

    /// Some frame of the arrival was well short of full width: it grew out of the cover.
    var zoomed: Bool { samples.contains { $0.frame.width > 0 && $0.frame.width < windowWidth * 0.9 } }
    /// The arrival faded in at full size: several frames part-way between transparent and opaque. The zoom does not
    /// count: its presented view sits transparent at full size while the system draws the flight elsewhere, then turns
    /// opaque in one step, so it never shows a part-way opacity.
    var dissolved: Bool {
        samples.filter { $0.frame.width >= windowWidth * 0.9 && $0.opacity > 0.05 && $0.opacity < 0.95 }.count >= 2
    }
    /// The default modal fallback: full width, sliding up from below.
    var slidUp: Bool { samples.contains { $0.frame.width >= windowWidth * 0.9 && $0.frame.minY > 1 } }

    /// The sample nearest 120 ms in, the moment the plan reads.
    var at120: Sample? { samples.min { abs($0.elapsedMs - 120) < abs($1.elapsedMs - 120) } }

    var description: String {
        let near = at120.map { "@\($0.elapsedMs)ms \(Self.format($0.frame)) α\($0.opacity)" } ?? "none"
        let all = samples.map { "\($0.elapsedMs)ms:\(Self.format($0.frame))α\(String(format: "%.2f", $0.opacity))" }
        return "zoomed=\(zoomed) dissolved=\(dissolved) slidUp=\(slidUp) window=\(windowWidth) near120=\(near) "
            + "samples=[\(all.joined(separator: " "))]"
    }

    private static func format(_ rect: CGRect) -> String {
        String(format: "(%.1f,%.1f %.1fx%.1f)", rect.minX, rect.minY, rect.width, rect.height)
    }

    /// Opens the player through the book button, as a tap or VoiceOver would, then samples the presented view until
    /// its transition ends.
    static func open(in hosted: HostedView) async throws -> PlayerFlight {
        let book = try #require(hosted.stops(labelContaining: "The Way of Kings").first, "\(hosted.tree)")
        try #require(hosted.activate(labelled: book.label), "the book button did not activate")
        let started = ContinuousClock.now
        var samples: [Sample] = []
        var settledFrames = 0
        while ContinuousClock.now - started < .seconds(3), settledFrames < 5 {
            if let presented = presented(in: hosted.window) {
                let elapsed = started.duration(to: .now)
                let layer = presented.view.layer.presentation() ?? presented.view.layer
                samples.append(Sample(
                    elapsedMs: Int(elapsed / .milliseconds(1)),
                    frame: layer.convert(layer.bounds, to: nil),
                    opacity: effectiveOpacity(layer)))
                if !presented.isBeingPresented { settledFrames += 1 }
            }
            try await Task.sleep(for: .milliseconds(16))
        }
        try #require(!samples.isEmpty, "the player was never presented")
        return PlayerFlight(windowWidth: hosted.window.bounds.width, samples: samples)
    }

    /// Closes the player through its own close button and waits for the cover to go.
    static func close(in hosted: HostedView) async throws {
        try #require(hosted.activate(labelled: PlayerStageContent.closeLabel), "\(hosted.tree)")
        try await waitUntil { presented(in: hosted.window) == nil }
        await hosted.settle()
    }

    static func waitUntil(_ condition: () -> Bool) async throws {
        let started = ContinuousClock.now
        while !condition(), ContinuousClock.now - started < .seconds(5) {
            try await Task.sleep(for: .milliseconds(20))
        }
        try #require(condition(), "timed out")
    }

    private static func presented(in window: UIWindow) -> UIViewController? {
        var top = window.rootViewController?.presentedViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }

    /// The opacity the layer is drawn at: its own times every ancestor's, as presented this frame.
    private static func effectiveOpacity(_ layer: CALayer) -> Float {
        var opacity = layer.opacity
        var ancestor = layer.superlayer
        while let current = ancestor {
            opacity *= current.opacity
            ancestor = current.superlayer
        }
        return opacity
    }
}

/// Counts what the focus-return test watches: the cover's dismissals and each time the book's focus binding turned on.
@MainActor
final class FocusProbe {
    var dismissals = 0
    var focusedBook = 0
}

/// The production shape: the flag is a property of an `@Observable` shell model the view holds in `@State`, bound
/// through `@Bindable`, exactly as `MainTabView` binds `MainShellModel.isPlayerPresented`. Focus returns to the book
/// through the same request and `returnsFocus` the mini player uses.
private struct ObservableFlagStage: View {
    @State private var shell: MainShellModel
    let model: MiniPlayerAccessoryModel
    let probe: FocusProbe?
    @State private var focusReturnRequest = 0
    @AccessibilityFocusState private var isBookFocused: Bool
    @Namespace private var namespace

    init(shell: MainShellModel, model: MiniPlayerAccessoryModel, probe: FocusProbe? = nil) {
        _shell = State(initialValue: shell)
        self.model = model
        self.probe = probe
    }

    var body: some View {
        @Bindable var shell = shell
        PlayerStageTabs()
            .tabViewBottomAccessory {
                MiniPlayerAccessoryContent(
                    model: model, isInline: false, transitionNamespace: namespace,
                    onOpen: { shell.isPlayerPresented = true }, onSkipBack: {}, onSkipForward: {}, onPrimary: {},
                    bookFocus: $isBookFocused)
                    .returnsFocus($isBookFocused, on: focusReturnRequest)
                    .onChange(of: isBookFocused) { _, focused in if focused { probe?.focusedBook += 1 } }
            }
            .playerPresentation(
                isPresented: $shell.isPlayerPresented, namespace: namespace,
                onDismiss: {
                    probe?.dismissals += 1
                    focusReturnRequest += 1
                }
            ) {
                if PlayerStageContent.isLoaded { PlayerStageContent(isPresented: $shell.isPlayerPresented) }
            }
    }
}

/// The shape the reports say still zooms: the flag is the view's own `@State`.
private struct ViewStateFlagStage: View {
    let model: MiniPlayerAccessoryModel
    @State private var isPlayerPresented = false
    @Namespace private var namespace

    var body: some View {
        PlayerStageTabs()
            .tabViewBottomAccessory {
                MiniPlayerAccessoryContent(
                    model: model, isInline: false, transitionNamespace: namespace,
                    onOpen: { isPlayerPresented = true }, onSkipBack: {}, onSkipForward: {}, onPrimary: {})
            }
            .playerPresentation(isPresented: $isPlayerPresented, namespace: namespace) {
                if PlayerStageContent.isLoaded { PlayerStageContent(isPresented: $isPlayerPresented) }
            }
    }
}

private struct PlayerStageTabs: View {
    var body: some View {
        TabView {
            Tab("Home", systemImage: "house") { Color(.systemBackground) }
            Tab("Library", systemImage: "books.vertical") { Color(.systemBackground) }
        }
    }
}

/// A stand-in for the full player: a full-screen page with a close button. The stages build it behind an `if`, as
/// production builds the player behind `if let playerObserver`, so the zoom lands on the same conditional shape.
private struct PlayerStageContent: View {
    static let closeLabel = "Close test player"
    /// Always true; it only makes the stage's content conditional.
    static var isLoaded: Bool { true }
    @Binding var isPresented: Bool

    var body: some View {
        ZStack {
            Color.indigo.ignoresSafeArea()
            Button(Self.closeLabel) { isPresented = false }
                .buttonStyle(.borderedProminent)
        }
    }
}
