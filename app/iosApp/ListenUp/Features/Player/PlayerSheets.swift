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

/// One chapter line — number · title · duration, with a now-playing equalizer on the current chapter and a play glyph
/// on the rest. The current chapter sits on a coral tint (plan C14: tinted on iOS) with "Now Playing · 8 min left";
/// others carry a leading-inset hairline. Shared by `ChapterListSheet` (the sheet) and the iPad inline "Up Next" panel
/// so both surfaces render chapters identically.
///
/// Text stays `.primary`/secondary on every row: the tint is decoration (the wash and the equalizer), never the colour
/// a title is read in. The current row is also `.isSelected` and says "now playing", so it is marked by more than colour.
/// VoiceOver hears one label per row (spec §8): "Chapter 6, Bridge Four, now playing, 8 min left", or "Play from
/// chapter 7: Anything Reasonable, 33:18". At accessibility text sizes the row stacks (HIG, Typography).
struct ChapterRow: View {
    /// The chapter's own number, as the book counts it.
    let number: Int
    let title: String
    let durationMs: Int64
    let isCurrent: Bool
    let isPlaying: Bool
    /// Content time left in the current chapter (S10); ignored on other rows.
    var remainingMs: Int64 = 0
    let tint: Color
    let onTap: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var isStacked: Bool { dynamicTypeSize.isAccessibilitySize }

    /// "Now Playing · 8 min left": the current row's visible line.
    static func currentLine(remainingMs: Int64) -> String {
        String(
            format: String(localized: "player.sheet_playing_left_ios"),
            MiniPlayerAccessoryModel.shortDuration(ms: remainingMs))
    }

    /// The row as VoiceOver reads it.
    var spokenLabel: String {
        if isCurrent {
            return String(
                format: String(localized: "player.sheet_chapter_current"), number, title,
                MiniPlayerAccessoryModel.shortDuration(ms: remainingMs))
        }
        return String(
            format: String(localized: "player.sheet_chapter_play_from"), number, title,
            DurationFormatting.clock(ms: durationMs))
    }

    var body: some View {
        let layout = isStacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(spacing: 14))
        Button(action: onTap) {
            layout {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text("\(number)")
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(isCurrent ? AnyShapeStyle(Color.luTintInk) : AnyShapeStyle(Color.luLabel3))
                        .frame(minWidth: 24)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.body)
                            .fontWeight(isCurrent ? .semibold : .regular)
                            .foregroundStyle(.primary)
                            .lineLimit(isStacked ? 3 : 1)
                        if isCurrent {
                            Text(Self.currentLine(remainingMs: remainingMs))
                                .font(.footnote)
                                .foregroundStyle(Color.secondary)
                        }
                    }
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
                    .fill(isCurrent ? tint.opacity(0.10) : .clear)
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
        .accessibilityLabel(spokenLabel)
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
    }
}

// MARK: - Chapter List Sheet

/// The Chapters sheet (player spec §5.5.4, PC1–PC4): "75 chapters · 45 hr 30 min", then every chapter, opening centred
/// on the one you're in — after the sheet has laid out, and without animating, since the jump isn't motion the reader
/// asked for (§9.2). Tapping a row plays from it and closes the sheet (PC4).
struct ChapterListSheet: View {
    let observer: PlayerObserver
    let onDismiss: () -> Void

    /// The row the list rests on, centred: the current chapter's from the first layout.
    @State private var centredRowId: String?

    init(observer: PlayerObserver, onDismiss: @escaping () -> Void) {
        self.observer = observer
        self.onDismiss = onDismiss
        _centredRowId = State(initialValue: Self.currentRowId(in: observer))
    }

    private static func currentRowId(in observer: PlayerObserver) -> String? {
        observer.chapterRows.first { $0.index == observer.chapterIndex }?.id
    }

    var body: some View {
        PlayerSheetChrome(title: String(localized: "player.chapters"), scrolls: false) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        Text(summary)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, Spacing.m)
                            .padding(.bottom, Spacing.xs)
                        ForEach(observer.chapterRows) { chapter in
                            ChapterRow(
                                number: chapter.number,
                                title: chapter.title,
                                durationMs: chapter.durationMs,
                                isCurrent: chapter.index == observer.chapterIndex,
                                isPlaying: observer.isPlaying,
                                remainingMs: observer.progress.chapterRemainingMs,
                                tint: .listenUpOrange,
                                onTap: {
                                    observer.selectChapter(index: chapter.index)
                                    onDismiss()
                                }
                            )
                            .id(chapter.id)
                        }
                    }
                    .padding(.horizontal, Spacing.xs)
                    .padding(.vertical, Spacing.xs)
                    .scrollTargetLayout()
                }
                // The scroll position starts on the current row, so the first layout is already centred (§9.2: centring
                // before the container has laid out is what left the web's list at chapter 1).
                .scrollPosition(id: $centredRowId, anchor: .center)
                .task {
                    // And once more after that layout, without animating: the jump isn't motion the reader asked for.
                    await Task.yield()
                    guard let current = Self.currentRowId(in: observer) else { return }
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) { proxy.scrollTo(current, anchor: .center) }
                }
            }
        }
    }

    /// "68 chapters · 1 hr, 8 min".
    private var summary: String {
        String(
            format: String(localized: "player.chapters_count_summary"),
            observer.totalChapters,
            DurationFormatting.abbreviated(ms: observer.bookDurationMs))
    }
}

// MARK: - Shared pieces

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
