import SwiftUI
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// The mini player accessory read the way VoiceOver meets it, and held to the accessory's fixed height.
@MainActor
@Suite("Mini player accessory accessibility", .serialized)
struct MiniPlayerAccessibilityTests {
    private static func model(_ state: PlayerUiStateLoaded) -> MiniPlayerAccessoryModel {
        MiniPlayerAccessoryModel.make(
            snapshot: PlayerSnapshot.from(state), chapterRemainingMs: 511_000, chapterFraction: 0.59,
            isInline: false, isWide: false)
    }

    private static func playingModel() -> MiniPlayerAccessoryModel {
        model(.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four"))
    }

    private let skipBackLabel = String(format: String(localized: "player.skip_backward"), "10")

    private func host(_ model: MiniPlayerAccessoryModel, _ size: DynamicTypeSize = .large) async -> HostedView {
        await HostedView(AccessoryHarness(model: model), size: CGSize(width: 361, height: 120), dynamicTypeSize: size)
    }

    @Test func theBookAndEachControlAreSeparateStops() async throws {
        let hosted = await host(Self.playingModel())
        defer { hosted.close() }
        let book = try #require(hosted.stops(labelContaining: "The Way of Kings").first, "\(hosted.tree)")
        #expect(book.isButton)
        #expect(book.label.contains("Bridge Four · 8 mins left"))
        let back = try #require(hosted.stop(labelled: skipBackLabel), "\(hosted.tree)")
        #expect(back.isButton)
        #expect(back.frame.height >= 44)
        let pause = try #require(hosted.stop(labelled: String(localized: "player.pause")), "\(hosted.tree)")
        #expect(pause.frame.width >= 44)
        // Siblings, never nested: the book's stop does not contain the buttons.
        #expect(!book.frame.contains(pause.frame))
    }

    @Test func atTheLargestTextTheAccessoryKeepsItsHeightAndEveryStop() async throws {
        let capped = await host(Self.playingModel(), .xLarge)
        let capHeight = capped.fittingHeight()
        capped.close()
        let largest = await host(Self.playingModel(), .accessibility5)
        defer { largest.close() }
        #expect(largest.fittingHeight() == capHeight)
        #expect(largest.stop(labelled: String(localized: "player.pause")) != nil, "\(largest.tree)")
        #expect(largest.stop(labelled: skipBackLabel) != nil)
    }

    @Test func aProblemOffersItsActionAndNoDismiss() async throws {
        let failed = Self.model(.fixture(
            phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false))
        let hosted = await host(failed)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: String(localized: "player.retry"))?.isButton == true, "\(hosted.tree)")
        #expect(hosted.stop(labelled: String(localized: "common.dismiss")) == nil)
        #expect(!hosted.stops(labelContaining: "Couldn't play this book.").isEmpty, "\(hosted.tree)")
    }

    @Test func theSleepFadeOffersKeepListeningBesideBackN() async throws {
        let fading = Self.model(.fixture(
            phase: PlayerPhaseSleepFading.shared, currentIndex: 5, primary: PrimaryControlPause.shared,
            chapterTitle: "Bridge Four"))
        let hosted = await host(fading)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Keep Listening")?.isButton == true, "\(hosted.tree)")
        #expect(hosted.stop(labelled: skipBackLabel) != nil)
        #expect(hosted.stop(labelled: String(localized: "player.pause")) == nil)
    }
}

/// Owns the zoom namespace the accessory's cover registers in.
struct AccessoryHarness: View {
    let model: MiniPlayerAccessoryModel
    var isInline = false
    @Namespace private var namespace

    var body: some View {
        MiniPlayerAccessoryContent(
            model: model, isInline: isInline, transitionNamespace: namespace,
            onOpen: {}, onSkipBack: {}, onSkipForward: {}, onPrimary: {})
    }
}
