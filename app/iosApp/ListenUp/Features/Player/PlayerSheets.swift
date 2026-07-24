import SwiftUI
import Shared

// MARK: - Speed Picker Sheet

/// Playback-speed picker: a large coral readout of the current rate, a continuous
/// slider snapped to the discrete catalogue, and a wrapped row of speed chips. The
/// slider has no continuous setter on the coordinator, so dragging snaps to the
/// nearest catalogued speed and routes through the same `onSpeedSelected` callback
/// the chips use — one entry point, never an off-catalogue rate.
struct SpeedPickerSheet: View {
    let currentSpeed: Float
    let onSpeedSelected: (Float) -> Void

    private let speeds: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]
    private let minSpeed: Float = 0.5
    private let maxSpeed: Float = 3.0
    @ScaledMetric(relativeTo: .largeTitle) private var speedReadoutSize: CGFloat = 56

    /// Live slider value, kept continuous for a smooth drag and snapped to the
    /// nearest catalogued speed on release.
    @State private var sliderValue: Double = 1.0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(Self.formatSpeed(currentSpeed))
                    .font(.system(size: speedReadoutSize, weight: .bold))
                    .monospacedDigit()
                    .foregroundStyle(Color.luTint)
                    .padding(.top, 8)

                slider
                    .padding(.top, 24)

                speedChips
                    .padding(.top, 24)

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .navigationTitle(String(localized: "player.playback_speed"))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { sliderValue = Double(currentSpeed) }
            .onChange(of: currentSpeed) { _, new in sliderValue = Double(new) }
        }
    }

    /// Continuous slider with min/max labels. On release the dragged value snaps to
    /// the nearest catalogued speed, which becomes the committed selection.
    private var slider: some View {
        VStack(spacing: 6) {
            Slider(
                value: $sliderValue,
                in: Double(minSpeed)...Double(maxSpeed),
                onEditingChanged: { editing in
                    guard !editing else { return }
                    let snapped = Self.snap(Float(sliderValue), to: speeds)
                    sliderValue = Double(snapped)
                    onSpeedSelected(snapped)
                }
            )
            .tint(Color.luTint)

            HStack {
                Text(Self.formatSpeed(minSpeed))
                Spacer()
                Text(Self.formatSpeed(maxSpeed))
            }
            .font(.footnote)
            .monospacedDigit()
            .foregroundStyle(Color.luLabel2)
        }
    }

    /// Wrapped catalogue of speeds; the active rate fills coral, the rest are neutral.
    private var speedChips: some View {
        FlowLayout(spacing: 9) {
            ForEach(speeds, id: \.self) { speed in
                PillButton(
                    title: Self.formatSpeed(speed),
                    isSelected: abs(speed - currentSpeed) < 0.001
                ) {
                    onSpeedSelected(speed)
                }
            }
        }
    }

    /// Nearest catalogued speed to an arbitrary value — the slider's snap rule.
    nonisolated static func snap(_ value: Float, to speeds: [Float]) -> Float {
        speeds.min(by: { abs($0 - value) < abs($1 - value) }) ?? value
    }

    /// "1.0×", "1.25×", "2×" — trailing-zero-trimmed with the multiplication sign.
    nonisolated static func formatSpeed(_ speed: Float) -> String {
        let rounded = (speed * 100).rounded() / 100
        if rounded == rounded.rounded() {
            return "\(Int(rounded))×"
        }
        var text = String(format: "%.2f", rounded)
        while text.hasSuffix("0") { text.removeLast() }
        return "\(text)×"
    }
}

// MARK: - Chapter Row

/// One chapter line — number · title · duration, with a now-playing equalizer on the
/// current chapter and a play glyph on the rest. The current chapter sits on a coral
/// wash with coral text; others carry a leading-inset hairline. Shared by
/// `ChapterListSheet` (the sheet) and the iPad inline "Up Next" panel so both
/// surfaces render chapters identically. `tint` highlights the current chapter (cover
/// accent on iPad, coral in the sheet).
struct ChapterRow: View {
    let index: Int
    let title: String
    let durationMs: Int64
    let isCurrent: Bool
    let isPlaying: Bool
    let tint: Color
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                Text("\(index + 1)")
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(isCurrent ? tint : Color.luLabel3)
                    .frame(width: 24)

                Text(title)
                    .font(.body)
                    .fontWeight(isCurrent ? .semibold : .regular)
                    .foregroundStyle(isCurrent ? tint : .primary)
                    .lineLimit(1)

                Spacer(minLength: 8)

                Text(DurationFormatting.clock(ms: durationMs))
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(isCurrent ? tint : Color.luLabel2)

                if isCurrent {
                    EqualizerGlyph(color: tint, isAnimating: isPlaying)
                } else {
                    Image(systemName: "play.fill")
                        .font(.caption)
                        .foregroundStyle(Color.luLabel3)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .frame(minHeight: 54)
            // Make the whole row tappable — without this the transparent gaps
            // (Spacer, the clear background of non-current rows) aren't hit-tested,
            // so only the text/glyphs register taps.
            .contentShape(Rectangle())
            .background(
                RoundedRectangle(cornerRadius: 11)
                    .fill(isCurrent ? tint.opacity(0.09) : .clear)
            )
            .overlay(alignment: .bottom) {
                if !isCurrent {
                    Rectangle()
                        .fill(Color.luSeparator)
                        .frame(height: 0.5)
                        .padding(.leading, 48)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Chapter List Sheet

/// Scrolling chapter list. The current chapter is coral-highlighted; tapping any row
/// seeks to that chapter and dismisses.
struct ChapterListSheet: View {
    let observer: PlayerCoordinator
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(0..<observer.totalChapters, id: \.self) { index in
                        ChapterRow(
                            index: index,
                            title: observer.chapterTitleForIndex(index) ?? "Chapter \(index + 1)",
                            durationMs: index < observer.chapters.count ? observer.chapters[index].duration : 0,
                            isCurrent: index == observer.chapterIndex,
                            isPlaying: observer.isPlaying,
                            tint: .listenUpOrange,
                            onTap: {
                                observer.selectChapter(index: index)
                                onDismiss()
                            }
                        )
                    }
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 8)
            }
            .navigationTitle(String(localized: "player.chapters"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Sleep Timer Sheet

/// Sleep-timer picker: an active-timer banner (when running) over a wrapped row of
/// duration chips and a coral "End of chapter" card. Selecting any option arms the
/// timer through the coordinator and dismisses.
struct SleepTimerSheet: View {
    let observer: PlayerCoordinator
    let onDismiss: () -> Void

    private let durations = [15, 30, 45, 60, 120]

    /// The active timer is "end of chapter" when its mode says so — used to tint the
    /// card and show its check.
    private var isEndOfChapterActive: Bool {
        observer.sleepTimerActive && observer.sleepTimerMode == "endOfChapter"
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                if observer.sleepTimerActive {
                    activeBanner
                }

                FlowLayout(spacing: 10) {
                    ForEach(durations, id: \.self) { minutes in
                        PillButton(
                            title: Self.formatDuration(minutes),
                            isSelected: false
                        ) {
                            observer.setSleepTimer(minutes: minutes)
                            onDismiss()
                        }
                    }
                }

                endOfChapterCard

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .navigationTitle(String(localized: "player.sleep_timer"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    /// Remaining-time banner with a Cancel action, shown only while a timer runs.
    private var activeBanner: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "player.timer_active"))
                    .font(.subheadline.bold())
                Text(observer.sleepTimerLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(String(localized: "common.cancel")) {
                observer.cancelSleepTimer()
                onDismiss()
            }
            .foregroundStyle(.red)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.luFill))
    }

    /// "End of chapter" option as a coral-iconed card; the whole card tints coral and
    /// shows a check when this mode is the active timer.
    private var endOfChapterCard: some View {
        Button {
            observer.setSleepTimerEndOfChapter()
            onDismiss()
        } label: {
            HStack(spacing: 14) {
                // Dynamic Type exclusion: sleep-timer icon glyph in fixed 42×42 circle
                Image(systemName: "moon.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(Color.luOnTint)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color.luTint))

                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "player.end_of_chapter"))
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                    Text(String(localized: "player.end_of_chapter_subtitle"))
                        .font(.caption)
                        .foregroundStyle(Color.luLabel2)
                }

                Spacer(minLength: 8)

                if isEndOfChapterActive {
                    Image(systemName: "checkmark")
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(Color.luOnTint)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Color.luTint))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(Color.luTint.opacity(isEndOfChapterActive ? 0.18 : 0.10))
            )
        }
        .buttonStyle(.plain)
    }

    /// "15 minutes", "1 hour", "2 hours" — the chip label for a duration in minutes.
    nonisolated static func formatDuration(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes) min" }
        if minutes == 60 { return "1 hour" }
        return "\(minutes / 60) hours"
    }
}

// MARK: - Shared pieces

/// A capsule chip used by the speed and sleep pickers. Coral-filled when selected,
/// neutral fill otherwise.
private struct PillButton: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .fontWeight(isSelected ? .semibold : .medium)
                .monospacedDigit()
                .foregroundStyle(isSelected ? Color.luOnTint : .primary)
                .padding(.horizontal, 18)
                .frame(height: 44)
                .background(Capsule().fill(isSelected ? AnyShapeStyle(Color.luTint) : AnyShapeStyle(Color.luFill)))
        }
        .buttonStyle(.pressScaleChip)
    }
}

/// Three-bar now-playing equalizer. Bars pulse while playing and hold static when
/// paused, honouring Reduce Motion.
private struct EqualizerGlyph: View {
    let color: Color
    let isAnimating: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase = false

    private let baseHeights: [CGFloat] = [6, 14, 9]
    private let peakHeights: [CGFloat] = [14, 7, 13]

    var body: some View {
        HStack(alignment: .center, spacing: 2.5) {
            ForEach(0..<3, id: \.self) { i in
                Capsule()
                    .fill(color)
                    .frame(width: 3, height: animate ? peakHeights[i] : baseHeights[i])
            }
        }
        .frame(width: 16, height: 16)
        .onAppear { phase = true }
        .animation(
            shouldPulse
                ? .easeInOut(duration: 0.45).repeatForever(autoreverses: true)
                : .default,
            value: animate
        )
    }

    private var shouldPulse: Bool { isAnimating && !reduceMotion }
    private var animate: Bool { shouldPulse && phase }
}
