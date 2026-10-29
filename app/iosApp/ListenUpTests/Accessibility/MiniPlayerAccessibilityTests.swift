import SwiftUI
import Testing
import UIKit
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
        #expect(book.label.contains("Bridge Four · \(systemMinutes(8)) left"))
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

    /// In the real accessory, the line's descenders never touch the progress hairline: at least 2pt of clear glass
    /// between them, at the default size and at the xLarge cap.
    @Test(arguments: [DynamicTypeSize.large, .xLarge])
    func theLineClearsTheHairline(_ size: DynamicTypeSize) async throws {
        let hosted = await HostedView(
            TabAccessoryHarness(model: Self.playingModel()), size: CGSize(width: 393, height: 852),
            dynamicTypeSize: size)
        defer { hosted.close() }
        let book = try #require(hosted.stops(labelContaining: "The Way of Kings").first, "\(hosted.tree)")
        // The text column: past the 34pt cover and its 10pt gap, where the hairline's played part runs beneath it.
        // It ends 8pt short of the skip-back button where the accessory is narrower than 110pt of text (beside the
        // closed iPhone Duo's vertical bar), so the button's glyph is not read as a band of the line's ink.
        let back = try #require(hosted.stop(labelled: skipBackLabel), "\(hosted.tree)")
        let columnStart = book.frame.minX + 46
        let columnWidth = min(110, back.frame.minX - 8 - columnStart)
        let column = CGRect(x: columnStart, y: book.frame.minY - 8, width: columnWidth, height: book.frame.height + 16)
        let runs = InkRuns(hosted.window).rows(in: column)
        // Title, line, hairline: three separate bands of ink, the last two at least 2pt apart.
        try #require(runs.count >= 3, "ink bands \(runs) in \(column)")
        let line = runs[runs.count - 2]
        let hairline = runs[runs.count - 1]
        #expect(hairline.lowerBound - line.upperBound >= 2, "line \(line) hairline \(hairline) at \(size)")
    }

    /// The swipe up is a gesture on the capsule, not a control: VoiceOver meets the same three stops (the book, back N,
    /// the main button) with no custom action, and opens the player through the book button as before.
    @Test func theSwipeAddsNoVoiceOverStopAndNoCustomAction() async throws {
        let plain = await host(Self.playingModel())
        let plainStops = plain.stops.map(\.label)
        plain.close()
        let swipeable = await HostedView(
            AccessoryHarness(model: Self.playingModel(), opensOnSwipe: true), size: CGSize(width: 361, height: 120))
        defer { swipeable.close() }
        #expect(swipeable.stops.map(\.label) == plainStops, "\(swipeable.tree)")
        #expect(swipeable.stops.count == 3, "\(swipeable.tree)")
        #expect(swipeable.stops.allSatisfy { $0.customActionNames.isEmpty }, "\(swipeable.tree)")
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

    @Test func theSleepFadeOffersKeepListeningAndGivesBackNsRoomToTheTitle() async throws {
        let fading = Self.model(.fixture(
            phase: PlayerPhaseSleepFading.shared, currentIndex: 5, primary: PrimaryControlPause.shared,
            chapterTitle: "Bridge Four"))
        let hosted = await host(fading)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Keep Listening")?.isButton == true, "\(hosted.tree)")
        #expect(hosted.stop(labelled: skipBackLabel) == nil)
        #expect(hosted.stop(labelled: String(localized: "player.pause")) == nil)
    }
}

/// Owns the zoom namespace the accessory's cover registers in.
struct AccessoryHarness: View {
    let model: MiniPlayerAccessoryModel
    var isInline = false
    var opensOnSwipe = false
    @Namespace private var namespace

    var body: some View {
        MiniPlayerAccessoryContent(
            model: model, isInline: isInline, transitionNamespace: namespace,
            onOpen: {}, onSkipBack: {}, onSkipForward: {}, onPrimary: {}, opensOnSwipe: opensOnSwipe)
    }
}

/// The accessory in a real system tab bar, so the measurement sees the capsule's own height.
private struct TabAccessoryHarness: View {
    let model: MiniPlayerAccessoryModel
    @Namespace private var namespace

    var body: some View {
        TabView {
            Tab("Home", systemImage: "house") { Color(.systemBackground) }
            Tab("Library", systemImage: "books.vertical") { Color(.systemBackground) }
        }
        .tabViewBottomAccessory {
            MiniPlayerAccessoryContent(
                model: model, isInline: false, transitionNamespace: namespace,
                onOpen: {}, onSkipBack: {}, onSkipForward: {}, onPrimary: {})
        }
    }
}

/// Bands of ink (text, or the hairline's coral) in a window region, read from what was drawn, in points.
@MainActor
private struct InkRuns {
    let image: CGImage
    let scale: CGFloat

    init(_ window: UIWindow) {
        let rendered = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        image = rendered.cgImage!
        scale = CGFloat(image.width) / window.bounds.width
    }

    /// Each vertical run of rows holding ink, as point ranges, top to bottom.
    func rows(in rect: CGRect) -> [ClosedRange<CGFloat>] {
        let width = image.width, height = image.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { bytes in
            let context = CGContext(
                data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            context?.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        let minX = max(0, Int(rect.minX * scale)), maxX = min(width, Int(rect.maxX * scale))
        let minY = max(0, Int(rect.minY * scale)), maxY = min(height, Int(rect.maxY * scale))
        var runs: [ClosedRange<CGFloat>] = []
        var start: Int?
        for y in minY..<maxY {
            var inked = false
            for x in stride(from: minX, to: maxX, by: 1) {
                let offset = (y * width + x) * 4
                if Int(pixels[offset]) + Int(pixels[offset + 1]) + Int(pixels[offset + 2]) < 600 { inked = true; break }
            }
            if inked, start == nil { start = y }
            if !inked, let first = start {
                runs.append(CGFloat(first) / scale...CGFloat(y) / scale)
                start = nil
            }
        }
        if let first = start { runs.append(CGFloat(first) / scale...CGFloat(maxY) / scale) }
        return runs
    }
}
