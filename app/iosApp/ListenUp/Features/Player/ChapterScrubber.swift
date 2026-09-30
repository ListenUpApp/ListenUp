import SwiftUI

/// What the chapter scrubber tells VoiceOver, split out of the view so it is testable.
enum ScrubberAccessibility {
    /// Which skip VoiceOver's swipe up/down performs.
    enum Skip: Equatable { case forward, backward }

    /// "12 minutes, 34 seconds of 45 minutes" — a spoken time, never raw milliseconds.
    static func value(elapsedMs: Int64, durationMs: Int64) -> String {
        String(
            format: String(localized: "player.scrubber_value_a11y"),
            spoken(ms: elapsedMs),
            spoken(ms: durationMs)
        )
    }

    /// Swipe up skips forward and swipe down skips back, by the listener's own intervals — the
    /// adjustment seeks straight away rather than nudging a thumb that only commits on release.
    static func skip(for direction: AccessibilityAdjustmentDirection) -> Skip? {
        switch direction {
        case .increment: .forward
        case .decrement: .backward
        @unknown default: nil
        }
    }

    private static func spoken(ms: Int64) -> String {
        Duration.seconds(max(0, ms) / 1000)
            .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
    }
}

/// The chapter slider + elapsed/remaining labels. Its own view so the per-frame position reads
/// that drive the moving thumb re-evaluate only this small view, not the whole player.
struct ChapterScrubberSection: View {
    let observer: PlayerCoordinator
    let tint: Color

    @State private var sliderPosition: Double = 0
    @State private var isDraggingSlider: Bool = false

    var body: some View {
        VStack(spacing: 8) {
            Slider(
                value: $sliderPosition,
                in: 0...max(Double(observer.chapterDurationMs), 1),
                onEditingChanged: { editing in
                    isDraggingSlider = editing
                    if !editing, let info = observer.currentChapterInfoForSeeking {
                        observer.seekTo(positionMs: Int64(info.startMs) + Int64(sliderPosition))
                    }
                }
            )
            .tint(tint)
            .accessibilityLabel(String(localized: "player.scrubber_a11y"))
            .accessibilityValue(ScrubberAccessibility.value(
                elapsedMs: observer.displayChapterPositionMs,
                durationMs: observer.chapterDurationMs
            ))
            .accessibilityAdjustableAction { direction in
                switch ScrubberAccessibility.skip(for: direction) {
                case .forward: observer.skipForward()
                case .backward: observer.skipBackward()
                case nil: break
                }
            }

            HStack {
                let elapsed = isDraggingSlider ? Int64(sliderPosition) : observer.chapterPositionMs
                Text(DurationFormatting.clock(ms: elapsed))
                Spacer()
                Text("-" + DurationFormatting.clock(ms: observer.chapterDurationMs - elapsed))
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .monospacedDigit()
            // The slider's value already speaks elapsed-of-duration.
            .accessibilityHidden(true)
        }
        .onChange(of: observer.chapterPositionMs) { _, newValue in
            if !isDraggingSlider {
                sliderPosition = Double(newValue)
            }
        }
        .onAppear {
            sliderPosition = Double(observer.chapterPositionMs)
        }
    }
}
