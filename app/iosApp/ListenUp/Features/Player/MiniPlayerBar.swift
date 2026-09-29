import SwiftUI
import Shared

/// The shared identity of the mini ↔ full player zoom: the mini player's cover is the
/// `matchedTransitionSource`, the full player's `fullScreenCover` zooms out of it.
enum PlayerTransition {
    static let coverID = "player-cover"
}

/// The mini player — the content of `MainTabView`'s `tabViewBottomAccessory`.
///
/// The system draws the Liquid Glass capsule, keeps the bar above the tab bar, moves it inline
/// when the tab bar minimizes on scroll, and reserves its space in every tab's safe area — HIG,
/// Tab bars: "For tab bars with an attached accessory, like the MiniPlayer in Music, you can
/// choose to minimize the tab bar and move the accessory inline with it". So this view draws no
/// glass or shadow of its own; it only lays out its contents for the current
/// `tabViewBottomAccessoryPlacement`.
///
/// Two sibling controls, never one nested in the other: the book (cover + titles) opens the full
/// player, and play/pause toggles playback.
struct MiniPlayerBar: View {
    let observer: PlayerCoordinator
    /// The namespace the cover registers in as the full player's zoom source.
    let transitionNamespace: Namespace.ID
    let onOpen: () -> Void

    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    /// Counts user taps on play/pause so the haptic fires only on a deliberate tap — not on
    /// programmatic `isPlaying` changes (audio-session interruptions, route changes, end of book).
    @State private var playPauseTapCount = 0
    /// The state the tap *moves to*, captured at tap time. Reading `isPlaybackActive` in the
    /// modifier would race the Kotlin StateFlow's trip through FlowBridge, so the verb could
    /// describe the state we just left. Mirrors Compose's `haptics.toggle(on = !isPlaying)`.
    @State private var playPauseWillBeActive = false

    /// Inline (beside the minimized tab bar) the accessory is short: title only, smaller cover.
    private var isInline: Bool { placement == .inline }

    var body: some View {
        Group {
            if observer.isErrored {
                errorRow
            } else {
                playerRow
            }
        }
        // The accessory is system chrome of a fixed height, like the tab bar: its text stops
        // growing at the largest standard size rather than clipping.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    // MARK: - Player row

    private var playerRow: some View {
        HStack(spacing: 8) {
            Button(action: onOpen) {
                HStack(spacing: 10) {
                    cover
                    titles
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(String(localized: "player.opens_player_hint"))

            playPauseButton
        }
        .padding(.leading, isInline ? 8 : 10)
        .padding(.trailing, 4)
        .overlay(alignment: .bottom) {
            if !isInline { progressLine }
        }
    }

    private var titles: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(observer.bookTitle)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
            if !isInline {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .lineLimit(1)
            }
        }
    }

    /// Never-stranded failure surface: instead of the player vanishing on `.error`, the bar shows
    /// the failure message and a Retry that re-drives the errored book (`togglePlayback` → replay).
    private var errorRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.listenUpOrange)
                .accessibilityHidden(true)

            Text(observer.errorMessage ?? String(localized: "common.something_went_wrong"))
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(1)

            Spacer(minLength: 4)

            Button { observer.togglePlayback() } label: {
                Text("book.detail_retry")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button { observer.dismissError() } label: {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "common.dismiss"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
    }

    /// "{Chapter} · {time left}" when a chapter is known, otherwise just the time left.
    /// Chapter-scoped, like every other now-playing surface (full player scrubber, lock screen,
    /// CarPlay), and read off the COARSE clock so the bar re-renders ~1×/s, not every frame.
    private var subtitle: String {
        if let chapter = observer.chapterTitle, !chapter.isEmpty {
            let remainingMs = max(0, observer.chapterDurationMs - observer.displayChapterPositionMs)
            return "\(chapter) · \(formatTimeLeft(remainingMs: remainingMs))"
        }
        return formatTimeLeft(remainingMs: observer.bookDurationMs - observer.displayBookPositionMs)
    }

    private var cover: some View {
        let side: CGFloat = isInline ? 28 : 36
        return BookCoverImage(bookId: observer.currentBookId, coverPath: observer.coverPath, coverHash: observer.coverHash)
            .frame(width: side, height: side)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .matchedTransitionSource(id: PlayerTransition.coverID, in: transitionNamespace) { source in
                source.clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            }
            .accessibilityHidden(true)
    }

    /// Chapter progress as a hairline along the capsule's lower edge, inset clear of its rounded
    /// ends. Chapter-scoped to match the subtitle (whole-book for chapterless books). Decorative:
    /// the subtitle already says the time left.
    private var progressLine: some View {
        GeometryReader { geometry in
            Capsule()
                .fill(Color.primary.opacity(0.08))
                .overlay(alignment: .leading) {
                    Capsule()
                        .fill(Color.listenUpOrange)
                        .frame(width: geometry.size.width * CGFloat(chapterProgress))
                }
        }
        .frame(height: 2)
        .padding(.horizontal, 22)
        .padding(.bottom, 3)
        .accessibilityHidden(true)
    }

    /// Fraction of the current chapter completed, `displayBookProgress` when chapterless.
    private var chapterProgress: Float {
        let durationMs = observer.chapterDurationMs
        guard durationMs > 0 else { return observer.displayBookProgress }
        return min(1, Float(observer.displayChapterPositionMs) / Float(durationMs))
    }

    private var playPauseButton: some View {
        Button {
            playPauseWillBeActive = !observer.isPlaybackActive
            playPauseTapCount += 1
            observer.togglePlayback()
        } label: {
            Group {
                // isPreparing piggybacks on the same buffering spinner: a play request in flight
                // (e.g. the user tapped a different book while this bar was still showing the
                // previous one) is visual feedback the user asked for, without a new affordance.
                if observer.isBuffering || observer.isPreparing {
                    // Honest buffering: a spinner while the stream loads, matching the full player
                    // and Android — not a pause glyph implying audio is already flowing.
                    ProgressView()
                        .controlSize(.small)
                        .tint(Color.listenUpOrange)
                } else {
                    // `isPlaybackActive` here means "playing" (buffering handled above); reads
                    // "pause" while playing because a tap pauses.
                    Image(systemName: observer.isPlaybackActive ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .foregroundStyle(Color.listenUpOrange)
                        .contentTransition(.symbolEffect(.replace.downUp))
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            observer.isPreparing
                ? String(localized: "book.detail_preparing")
                : (observer.isPlaybackActive
                    ? String(localized: "player.pause")
                    : String(localized: "player.play"))
        )
        .haptic(playPauseWillBeActive ? .toggleOn : .toggleOff, trigger: playPauseTapCount)
    }
}
