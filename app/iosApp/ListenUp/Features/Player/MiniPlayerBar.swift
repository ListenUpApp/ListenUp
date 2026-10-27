import SwiftUI
import Shared

/// The shared identity of the mini ↔ full player zoom: the mini player's cover is the
/// `matchedTransitionSource`, the full player's `fullScreenCover` zooms out of it.
enum PlayerTransition {
    static let coverID = "player-cover"
}

/// The mini player: `MainTabView`'s `tabViewBottomAccessory`.
///
/// The system draws the Liquid Glass capsule, keeps the bar above the tab bar, moves it inline when the tab bar
/// minimises on scroll, and reserves its space in every tab's safe area (HIG, Tab bars: "For tab bars with an
/// attached accessory, like the MiniPlayer in Music, you can choose to minimize the tab bar and move the accessory
/// inline with it"). So this draws no glass of its own: it reads the placement and the width, builds the model, and
/// lays it out.
struct MiniPlayerBar: View {
    let observer: PlayerObserver
    /// The namespace the cover registers in as the full player's zoom source.
    let transitionNamespace: Namespace.ID
    let onOpen: () -> Void

    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let isInline = placement == .inline
        let model = MiniPlayerAccessoryModel.make(
            snapshot: observer.snapshot,
            chapterRemainingMs: max(0, observer.chapterDurationMs - observer.displayChapterPositionMs),
            chapterFraction: chapterFraction,
            isInline: isInline,
            isWide: sizeClass == .regular)
        MiniPlayerBookSwitchTransition(key: model.switchKey, reduceMotion: reduceMotion) {
            MiniPlayerAccessoryContent(
                model: model, isInline: isInline, transitionNamespace: transitionNamespace,
                onOpen: onOpen,
                onSkipBack: { observer.skipBackward() },
                onSkipForward: { observer.skipForward() },
                onPrimary: { observer.miniPrimary(model.primaryAction) },
                opensOnSwipe: MiniPlayerSwipe.isEnabled(placement: placement))
        }
        // Said once, politely, when a dropped connection starts recovering; never per second (HIG, Accessibility).
        .onChange(of: model.isReconnecting) { _, isReconnecting in
            guard isReconnecting else { return }
            AccessibilityNotification.Announcement(String(localized: "player.reconnecting")).post()
        }
    }

    /// Chapter progress off the COARSE clock, so the hairline redraws about once a second, not per frame.
    private var chapterFraction: Float {
        let duration = observer.chapterDurationMs
        guard duration > 0 else { return observer.displayBookProgress }
        return min(1, Float(observer.displayChapterPositionMs) / Float(duration))
    }
}

/// How the mini player moves on a book switch (Simon, 2026-10-14): the old book goes out and the new one comes in,
/// so the change reads as deliberate rather than a one-frame flicker. Reduce Motion swaps instantly.
enum MiniPlayerBookSwitch {
    /// The two ways a switch can look.
    enum Style: Equatable {
        /// The system's content-replacement transition (`.blurReplace`), on the system's smooth spring.
        case replace
        /// No transition: the new book simply takes the old one's place.
        case instant
    }

    static func style(reduceMotion: Bool) -> Style { reduceMotion ? .instant : .replace }

    static func animation(reduceMotion: Bool) -> Animation? {
        switch style(reduceMotion: reduceMotion) {
        case .replace: .smooth
        case .instant: nil
        }
    }

    static func transition(reduceMotion: Bool) -> AnyTransition {
        switch style(reduceMotion: reduceMotion) {
        case .replace: AnyTransition(.blurReplace)
        case .instant: .identity
        }
    }
}

/// Re-identifies its content by `key` (the book), so a switch transitions the old content out and the new in. The
/// two overlap in one `ZStack` while it runs, so the bar is never empty for a frame; a change that keeps the key (a
/// pause, a tick) is an ordinary update and never animates as a switch.
struct MiniPlayerBookSwitchTransition<Content: View>: View {
    let key: String?
    let reduceMotion: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            content()
                .id(key)
                .transition(MiniPlayerBookSwitch.transition(reduceMotion: reduceMotion))
        }
        .animation(MiniPlayerBookSwitch.animation(reduceMotion: reduceMotion), value: key)
    }
}

/// The accessory's layout, from a value: the book (one button) · back N · the main button · forward (iPad). Pure, so
/// the accessibility tests and the review snapshots host it directly.
struct MiniPlayerAccessoryContent: View {
    let model: MiniPlayerAccessoryModel
    let isInline: Bool
    let transitionNamespace: Namespace.ID
    let onOpen: () -> Void
    let onSkipBack: () -> Void
    let onSkipForward: () -> Void
    let onPrimary: () -> Void
    /// Whether a swipe up opens the full player (`MiniPlayerSwipe`): the expanded capsule only.
    var opensOnSwipe = false

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onOpen) {
                HStack(spacing: coverGap) {
                    cover
                    titles
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel([model.title, model.line].compactMap(\.self).joined(separator: ", "))
            .accessibilityHint(String(localized: "player.opens_player_hint"))
            .accessibilityShowsLargeContentViewer { Label(model.title, systemImage: "book") }

            if model.showsSkipBack {
                skip(symbol: PlayerGlyphs.skipBackward(seconds: model.skipBackSec),
                     label: String(format: String(localized: "player.skip_backward"), "\(model.skipBackSec)"),
                     action: onSkipBack)
            }
            MiniPlayerPrimaryButton(model: model, action: onPrimary)
            if model.showsSkipForward {
                skip(symbol: PlayerGlyphs.skipForward(seconds: model.skipForwardSec),
                     label: String(format: String(localized: "player.skip_forward"), "\(model.skipForwardSec)"),
                     action: onSkipForward)
                    .disabled(!model.canSkipForward)
            }
        }
        .padding(.leading, leadingInset)
        // A labelled capsule that ends the row sits concentric inside the accessory's end cap: the same 12pt clear on
        // its end as above and below it. A glyph, or a capsule followed by iPad's forward, keeps the tight inset, so
        // forward holds one position in every state.
        .padding(.trailing, model.primary.isPill && !model.showsSkipForward ? Spacing.s : Spacing.xxs)
        .containerShape(Capsule())
        .overlay(alignment: .bottom) { if model.showsProgress { progressLine } }
        // A trigger, not a drag: the swipe opens the same zoom a tap does and never follows the finger. Simultaneous,
        // so the book, skip and play buttons keep their taps; a gesture adds no VoiceOver stop or action (the book
        // button already opens the player).
        .simultaneousGesture(
            DragGesture(minimumDistance: 8).onEnded { drag in
                if MiniPlayerSwipe.opens(
                    translation: drag.translation.height, predictedEnd: drag.predictedEndTranslation.height) {
                    onOpen()
                }
            },
            including: opensOnSwipe ? .all : .subviews)
        // The accessory is system chrome of a fixed height, like the tab bar: its text stops growing at xLarge, and
        // the large content viewer shows each control instead (HIG, Accessibility). MiniPlayerAccessibilityTests
        // pins the height.
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
    }

    private var leadingInset: CGFloat { isInline ? Spacing.xs : Spacing.s }
    private var coverSide: CGFloat { isInline ? 28 : 34 }
    /// The gap between the cover and the titles.
    private let coverGap: CGFloat = 10

    private var titles: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(model.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
            MiniPlayerLineText(model: model)
        }
        // Lifts the text clear of the progress hairline: the line's descenders keep 2pt of glass above it, up to the
        // xLarge cap (MiniPlayerAccessibilityTests pins the gap).
        .padding(.bottom, Spacing.xxs)
    }

    /// The cover sits concentric with the accessory's capsule (HIG, Layout: concentric corners inside Liquid Glass).
    private var cover: some View {
        BookCoverImage(bookId: model.bookId, coverPath: model.coverPath, coverHash: model.coverHash)
            .frame(width: coverSide, height: coverSide)
            .clipShape(.concentric(minimum: Radius.s))
            // The zoom source only takes a rounded rectangle; the full player's cover corner is Radius.s.
            .matchedTransitionSource(id: PlayerTransition.coverID, in: transitionNamespace) { source in
                source.clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
            }
            .accessibilityHidden(true)
    }

    private func skip(symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.primary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityShowsLargeContentViewer()
    }

    /// Chapter progress as a 2pt hairline inside the capsule, clear of its rounded ends. Decorative: the line says
    /// the time left.
    private var progressLine: some View {
        GeometryReader { geometry in
            Capsule()
                .fill(Color.primary.opacity(0.08))
                .overlay(alignment: .leading) {
                    Capsule()
                        .fill(Color.listenUpOrange)
                        .frame(width: geometry.size.width * CGFloat(model.chapterFraction))
                }
        }
        .frame(height: 2)
        // It starts at the text column, so it never crosses the cover.
        .padding(.leading, leadingInset + coverSide + coverGap)
        .padding(.trailing, Spacing.xl)
        .padding(.bottom, 1)
        .accessibilityHidden(true)
    }
}

/// The accessory's one main button, shared with the reader's strip: the coral glyph (HIG, Playing audio: no fill), a
/// `ProgressView` in the glyph's coral while busy, a labelled capsule for a way out (or Keep Listening, Listen Again), or ✕.
struct MiniPlayerPrimaryButton: View {
    /// Play and pause's coral (HIG, Playing audio: the glyph in the tint, no fill).
    static let glyphTint = Color.listenUpOrange
    /// Buffering keeps the control's look: the spinner in the glyph's own colour (Simon, 2026-10-27).
    static let busyTint = glyphTint

    let model: MiniPlayerAccessoryModel
    let action: () -> Void

    /// Counts deliberate taps, so the haptic never fires on a programmatic change (an interruption, a route change).
    @State private var taps = 0
    /// The state a tap moves to, captured at tap time: reading the model in the modifier would race the shared
    /// state's trip through FlowBridge and could name the state just left.
    @State private var tapStartsPlayback = false

    var body: some View {
        Button {
            tapStartsPlayback = model.primary != .pause
            taps += 1
            action()
        } label: {
            label
        }
        .buttonStyle(.plain)
        .accessibilityLabel(model.primaryLabel)
        .accessibilityShowsLargeContentViewer()
        .haptic(tapStartsPlayback ? .toggleOn : .toggleOff, trigger: taps)
    }

    @ViewBuilder
    private var label: some View {
        switch model.primary {
        case .play, .pause:
            Image(systemName: model.primary == .pause ? "pause.fill" : "play.fill")
                .font(.title3)
                .foregroundStyle(Self.glyphTint)
                .contentTransition(.symbolEffect(.replace.downUp))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        case .busy:
            ProgressView()
                .controlSize(.small)
                .tint(Self.busyTint)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        case .close:
            Image(systemName: "xmark")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        case .pill(let text, let prominent):
            // A compact capsule inside the accessory's glass, its target still 44pt tall (HIG, Buttons).
            Text(text)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(prominent ? Color.luOnTint : Color.luTintInk)
                .lineLimit(1)
                .padding(.horizontal, Spacing.s)
                .padding(.vertical, Spacing.xxs + 2)
                .background(Capsule().fill(prominent ? Color.listenUpOrange : Color.listenUpOrange.opacity(0.12)))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
    }
}

/// The mini line as two parts: the lead ("Paused · Bridge Four") truncates first, and the tail ("8 mins left") keeps
/// its full width, so the time left is never the part that disappears. A failure is a red glyph and red words on the
/// plain glass (Simon, 2026-10-14): the capsule is the system's and stays untinted, and the glyph keeps the meaning off
/// colour alone. Shared with the reader's strip.
struct MiniPlayerLineText: View {
    let model: MiniPlayerAccessoryModel

    var body: some View {
        if let lead = model.lineLead {
            HStack(spacing: 0) {
                if model.isFailure {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .imageScale(.small)
                        .padding(.trailing, 3)
                        .accessibilityHidden(true)
                }
                Text(lead)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let tail = model.lineTail {
                    Text(" · \(tail)")
                        .monospacedDigit()
                        .lineLimit(1)
                        .fixedSize()
                        .layoutPriority(1)
                }
            }
            .font(.caption)
            .foregroundStyle(model.isFailure ? Color.luPlayerError : Color.secondary)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(model.line ?? lead)
        }
    }
}
