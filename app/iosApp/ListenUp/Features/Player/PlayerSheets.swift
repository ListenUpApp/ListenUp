import SwiftUI

// MARK: - Boost Picker Sheet

/// Volume-boost picker: a large coral readout of the current boost, a wrapped row of
/// dB chips, and a "Use default" row that resets the book back to the global default.
/// No slider — unlike speed, boost is a small discrete catalogue (design-locked), so a
/// continuous drag would only invite off-catalogue values with no real benefit.
struct BoostPickerSheet: View {
    let currentBoostDb: Float
    /// The global default, or `nil` while it is still unknown — see `useDefaultRow`.
    let defaultBoostDb: Float?
    let onBoostSelected: (Float) -> Void
    let onUseDefault: () -> Void

    // Mirrors VolumeBoostLimits.MIN_DB...MAX_DB in :contract (not exported to Swift).
    private let steps: [Float] = [0, 3, 6, 9, 12]
    @ScaledMetric(relativeTo: .largeTitle) private var boostReadoutSize: CGFloat = 56

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(Self.formatBoost(currentBoostDb))
                    .font(.system(size: boostReadoutSize, weight: .bold)) // scaled via @ScaledMetric
                    .monospacedDigit()
                    .foregroundStyle(Color.luTint)
                    .padding(.top, 8)

                boostChips
                    .padding(.top, 24)

                useDefaultRow

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .navigationTitle(String(localized: "player.volume_boost"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    /// Wrapped catalogue of boost steps; the active step fills coral, the rest are neutral.
    private var boostChips: some View {
        FlowLayout(spacing: 9) {
            ForEach(steps, id: \.self) { step in
                PillButton(
                    title: Self.formatBoost(step),
                    isSelected: abs(step - currentBoostDb) < 0.001
                ) {
                    onBoostSelected(step)
                }
            }
        }
    }

    /// Shown whenever the default is known — harmless when the book is already at it, and it
    /// avoids plumbing a separate "has a custom boost" flag through the coordinator. Hidden while
    /// it is `nil`, because a row that reads "Use default (Off)" on an unresolved read would
    /// silently apply 0 dB over a real +6 default.
    @ViewBuilder
    private var useDefaultRow: some View {
        if let defaultBoostDb {
            Button(action: onUseDefault) {
                Text(String(format: String(localized: "player.boost_use_default"), Self.formatBoost(defaultBoostDb)))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Color.secondary)
            }
            .buttonStyle(.plain)
            .padding(.top, 20)
        }
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
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
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
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
        .accessibilityValue(isCurrent ? String(localized: "player.now_playing") : "")
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
                .padding(.horizontal, 6)
                .padding(.vertical, 8)
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
                .padding(.horizontal, 18)
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
