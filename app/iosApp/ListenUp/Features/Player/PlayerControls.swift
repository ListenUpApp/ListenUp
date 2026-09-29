import SwiftUI

// MARK: - Transport

/// Previous chapter · skip back · play/pause · skip forward · next chapter. Every target is at
/// least 44pt (HIG, Accessibility: controls need a 44×44pt hit area).
struct PlayerTransportControls: View {
    let observer: PlayerCoordinator

    /// Counts deliberate transport taps so the haptic fires on the tap, never on a state change
    /// arriving from elsewhere (a remote command, the lock screen). Mirrors `MiniPlayerBar`.
    @State private var transportTapCount = 0
    @State private var playPauseTapCount = 0
    /// The state the tap *moves to*, captured at tap time. Reading `isPlaybackActive` in the
    /// modifier would race the Kotlin StateFlow's trip through FlowBridge, so the verb could
    /// describe the state we just left. Mirrors Compose's `haptics.toggle(on = !isPlaying)`.
    @State private var playPauseWillBeActive = false

    @ScaledMetric(relativeTo: .title) private var playButtonSize: CGFloat = 76

    private var hasPreviousChapter: Bool { observer.chapterIndex > 0 }
    private var hasNextChapter: Bool { observer.chapterIndex < observer.totalChapters - 1 }

    var body: some View {
        HStack(spacing: 0) {
            transportButton(
                systemImage: "backward.end.fill",
                font: .title3,
                label: String(localized: "player.previous_chapter"),
                isEnabled: hasPreviousChapter
            ) {
                observer.selectChapter(index: observer.chapterIndex - 1)
            }

            Spacer(minLength: 4)

            transportButton(
                systemImage: PlayerGlyphs.skipBackward(seconds: observer.skipBackwardSec),
                font: .title2,
                label: String(format: String(localized: "player.skip_backward"), "\(observer.skipBackwardSec)")
            ) {
                observer.skipBackward()
            }

            Spacer(minLength: 4)

            playPauseButton

            Spacer(minLength: 4)

            transportButton(
                systemImage: PlayerGlyphs.skipForward(seconds: observer.skipForwardSec),
                font: .title2,
                label: String(format: String(localized: "player.skip_forward"), "\(observer.skipForwardSec)")
            ) {
                observer.skipForward()
            }

            Spacer(minLength: 4)

            transportButton(
                systemImage: "forward.end.fill",
                font: .title3,
                label: String(localized: "player.next_chapter"),
                isEnabled: hasNextChapter
            ) {
                observer.selectChapter(index: observer.chapterIndex + 1)
            }
        }
        .haptic(.press, trigger: transportTapCount)
        .haptic(playPauseWillBeActive ? .toggleOn : .toggleOff, trigger: playPauseTapCount)
    }

    private func transportButton(
        systemImage: String,
        font: Font,
        label: String,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            transportTapCount += 1
            action()
        } label: {
            Image(systemName: systemImage)
                .font(font)
                .foregroundStyle(isEnabled ? .primary : .tertiary)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityLabel(label)
    }

    /// The coral play/pause disc. No coloured glow beneath it: a tinted shadow reads as a second,
    /// blurred control and the HIG keeps depth for layering, not decoration.
    private var playPauseButton: some View {
        Button {
            playPauseWillBeActive = !observer.isPlaybackActive
            playPauseTapCount += 1
            observer.togglePlayback()
        } label: {
            ZStack {
                Circle()
                    .fill(Color.listenUpOrange)
                if observer.isBuffering {
                    // Honest buffering: a spinner while the stream loads, not a pause glyph
                    // that implies audio is flowing when it isn't yet.
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.large)
                        .tint(Color.luOnTint)
                } else {
                    // `isPlaybackActive` here means "playing" (buffering handled above); it
                    // reads "pause" while playing because a tap pauses.
                    Image(systemName: observer.isPlaybackActive ? "pause.fill" : "play.fill")
                        .font(.title)
                        .foregroundStyle(Color.luOnTint)
                        .contentTransition(.symbolEffect(.replace.downUp))
                }
            }
            .frame(width: playButtonSize, height: playButtonSize)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: observer.isPlaybackActive ? "player.pause" : "player.play"))
    }
}

// MARK: - Secondary controls

/// Speed · Sleep · Chapters · AirPlay · Boost, each a 44pt control over a caption. Each control
/// names itself to VoiceOver (label + value), so the visible caption is hidden from it rather than
/// read twice. At accessibility text sizes the row becomes a list of control-beside-caption rows,
/// which the player column scrolls (HIG, Typography: "consider using a stacked layout").
struct PlayerSecondaryControls: View {
    let observer: PlayerCoordinator
    let tint: Color
    let showsChaptersControl: Bool
    let onShowChapters: () -> Void
    let onShowBoost: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var isStacked: Bool { dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        let layout = isStacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 6))
        layout {
            item(caption: String(localized: "player.speed")) {
                PlaybackSpeedMenu(observer: observer) {
                    pill(PlaybackRates.format(observer.playbackSpeed))
                }
            }

            item(caption: String(localized: "player.sleep")) {
                SleepTimerMenu(observer: observer) {
                    glyph(observer.sleepTimerActive ? "moon.zzz.fill" : "moon.zzz", tinted: observer.sleepTimerActive)
                }
            }

            if showsChaptersControl {
                item(caption: String(localized: "player.chapters")) {
                    Button(action: onShowChapters) { glyph("list.bullet") }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(localized: "player.chapters"))
                }
            }

            // AirPlay's system route picker voices itself; its caption stays readable beside it.
            item(caption: String(localized: "player.airplay"), captionIsSpoken: true) {
                RoutePickerView(tint: Color(.label), activeTint: tint)
                    .frame(width: 44, height: 44)
            }

            item(caption: String(localized: "player.boost")) {
                Button(action: onShowBoost) { pill(BoostPickerSheet.formatBoostPill(observer.volumeBoostDb)) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "player.volume_boost"))
                    .accessibilityValue(BoostPickerSheet.formatBoost(observer.volumeBoostDb))
            }
        }
    }

    private func item(
        caption: String,
        captionIsSpoken: Bool = false,
        @ViewBuilder _ control: () -> some View
    ) -> some View {
        let layout = isStacked
            ? AnyLayout(HStackLayout(spacing: 12))
            : AnyLayout(VStackLayout(spacing: 2))
        return layout {
            control()
            Text(caption)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .accessibilityHidden(!captionIsSpoken)
        }
        .frame(maxWidth: isStacked ? nil : .infinity, alignment: isStacked ? .leading : .center)
    }

    /// A capsule value pill: the capsule is drawn at its natural size, the hit area is 44pt tall.
    private func pill(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .monospacedDigit()
            .foregroundStyle(.primary)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, Spacing.xxs)
            .frame(minHeight: 28)
            .background(Color(.tertiarySystemFill), in: Capsule())
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }

    private func glyph(_ systemImage: String, tinted: Bool = false) -> some View {
        Image(systemName: systemImage)
            .font(.title3)
            .foregroundStyle(tinted ? tint : .primary)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }
}
