import SwiftUI

// MARK: - Boost Picker Sheet

/// Volume Boost (player spec §5.5.3, PB1–PB5; the `Sheets-iPhone` board): the coral readout, the five levels as a
/// segmented control, the note, and Reset. HIG, Segmented controls: one choice among a few closely related ones. It
/// stays open after a pick, so the reader can hear each level and settle (PB5); the close button or a swipe ends it.
struct BoostPickerSheet: View {
    let currentBoostDb: Float
    /// The global default, or `nil` while it is still unknown — see `resetRow`.
    let defaultBoostDb: Float?
    let onBoostSelected: (Float) -> Void
    let onUseDefault: () -> Void

    // Mirrors VolumeBoostLimits.PRESETS_DB in :contract (not exported to Swift).
    static let steps: [Float] = [0, 3, 6, 9, 12]
    @ScaledMetric(relativeTo: .largeTitle) private var boostReadoutSize: CGFloat = 56

    var body: some View {
        PlayerSheetChrome(title: String(localized: "player.sheet_boost_title_ios")) {
            VStack(spacing: Spacing.l) {
                Text(Self.formatBoost(currentBoostDb))
                    .font(.system(size: boostReadoutSize, weight: .bold)) // scaled via @ScaledMetric
                    .monospacedDigit()
                    .foregroundStyle(Color.luTintInk)
                    // The selected segment says the same; the readout would say it twice.
                    .accessibilityHidden(true)

                Picker(String(localized: "player.sheet_boost_level"), selection: selection) {
                    ForEach(Self.steps, id: \.self) { step in
                        Text(Self.segmentLabel(step)).tag(step)
                    }
                }
                .pickerStyle(.segmented)
                // HIG, Accessibility: 44pt targets; the regular segmented control is 32pt tall.
                .controlSize(.extraLarge)

                Text(String(localized: "player.sheet_boost_note"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                resetRow
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, Spacing.l)
            .padding(.vertical, Spacing.m)
        }
    }

    /// The segment holding the current boost; a level between the steps (from another client) selects the nearest.
    private var selection: Binding<Float> {
        Binding(
            get: { Self.steps.min { abs($0 - currentBoostDb) < abs($1 - currentBoostDb) } ?? 0 },
            set: { onBoostSelected($0) }
        )
    }

    /// Shown away from the default. Hidden while the default is `nil`, because a row that read "Reset to Off" on an
    /// unresolved read would silently apply 0 dB over a real +6 default.
    @ViewBuilder
    private var resetRow: some View {
        if let defaultBoostDb, abs(defaultBoostDb - currentBoostDb) >= 0.01 {
            Button(String(format: String(localized: "player.sheet_reset_boost_ios"), Self.formatBoost(defaultBoostDb))) {
                onUseDefault()
            }
            .buttonStyle(.borderless)
            .frame(minHeight: TapTarget.minimum)
        }
    }

    /// A segment's words: "Off", then "+3" … "+12" (the readout above carries the unit).
    nonisolated static func segmentLabel(_ db: Float) -> String {
        let rounded = Int(db.rounded())
        if rounded == 0 { return String(localized: "player.boost_off") }
        return String(format: String(localized: "player.boost_pill_db"), rounded)
    }

    /// "Off" for no boost, else "+N dB" — the readout and chip label shared shape.
    nonisolated static func formatBoost(_ db: Float) -> String {
        let rounded = Int(db.rounded())
        if rounded == 0 { return String(localized: "player.boost_off") }
        return String(format: String(localized: "player.boost_db"), rounded)
    }

    /// The pill variant: "0 dB" at the floor, else "+N" with no unit so the control never
    /// truncates. The floor spells the unit out because the pill carries no label of its own —
    /// "Off" never said WHAT was off — and naming it there is what makes the bare "+N" legible.
    nonisolated static func formatBoostPill(_ db: Float) -> String {
        let rounded = Int(db.rounded())
        if rounded == 0 { return String(localized: "player.boost_pill_zero_db") }
        return String(format: String(localized: "player.boost_pill_db"), rounded)
    }
}

// MARK: - Chapter Row

/// One chapter line — number · title · duration, with a now-playing equalizer on the
/// current chapter and a play glyph on the rest. The current chapter sits on a tinted
/// wash; others carry a leading-inset hairline. Shared by `ChapterListSheet` (the sheet)
/// and the iPad inline "Up Next" panel so both surfaces render chapters identically.
///
/// Text stays `.primary`/secondary on every row: the tint is decoration (the wash and the
/// equalizer), never the colour a title is read in — a cover-derived tint can't promise text
/// contrast. The current row is also `.isSelected` and says "Now playing", so it is marked by
/// more than colour. At accessibility text sizes the row stacks (HIG, Typography: "consider
/// using a stacked layout where text appears above secondary items").
struct ChapterRow: View {
    let index: Int
    let title: String
    let durationMs: Int64
    let isCurrent: Bool
    let isPlaying: Bool
    let tint: Color
    let onTap: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var isStacked: Bool { dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        let layout = isStacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(spacing: 14))
        Button(action: onTap) {
            layout {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text("\(index + 1)")
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(isCurrent ? AnyShapeStyle(.primary) : AnyShapeStyle(Color.luLabel3))
                        .frame(minWidth: 24)

                    Text(title)
                        .font(.body)
                        .fontWeight(isCurrent ? .semibold : .regular)
                        .foregroundStyle(.primary)
                        .lineLimit(isStacked ? 3 : 1)
                }

                if !isStacked {
                    Spacer(minLength: 8)
                }

                HStack(spacing: 14) {
                    Text(DurationFormatting.clock(ms: durationMs))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(Color.secondary)

                    if isCurrent {
                        EqualizerGlyph(color: tint, isAnimating: isPlaying)
                    } else {
                        Image(systemName: "play.fill")
                            .font(.caption)
                            .foregroundStyle(Color.luLabel3)
                    }
                }
            }
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.s)
            .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
            // Make the whole row tappable — without this the transparent gaps
            // (Spacer, the clear background of non-current rows) aren't hit-tested,
            // so only the text/glyphs register taps.
            .contentShape(Rectangle())
            .background(
                RoundedRectangle(cornerRadius: Radius.m)
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
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
        .accessibilityValue(isCurrent ? String(localized: "player.now_playing") : "")
    }
}

// MARK: - Chapter List Sheet

/// Scrolling chapter list. The current chapter is coral-highlighted; tapping any row
/// seeks to that chapter and dismisses.
struct ChapterListSheet: View {
    let observer: PlayerObserver
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(Array(observer.chapterRows.enumerated()), id: \.element.id) { index, chapter in
                        ChapterRow(
                            index: index,
                            title: chapter.title,
                            durationMs: chapter.durationMs,
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
                .padding(.horizontal, Spacing.xs)
                .padding(.vertical, Spacing.xs)
            }
            .navigationTitle(String(localized: "player.chapters"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Shared pieces

/// A capsule chip used by the boost picker. Coral-filled when selected, neutral fill otherwise;
/// the selected chip also carries the VoiceOver selected trait, so the state isn't colour alone.
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
                .padding(.horizontal, Spacing.l)
                .frame(height: 44)
                .background(Capsule().fill(isSelected ? AnyShapeStyle(Color.luTint) : AnyShapeStyle(Color.luFill)))
        }
        .buttonStyle(.pressScaleChip)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
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
