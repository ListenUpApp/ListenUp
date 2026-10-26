import SwiftUI

// MARK: - Transport

/// Previous chapter · skip back · play/pause · skip forward · next chapter. Every target is at
/// least 44pt (HIG, Accessibility: controls need a 44×44pt hit area).
///
/// Previous always works while the transport shows: at a chapter's start it goes to the one before, and in chapter 1
/// it restarts it (player spec §3.3). Next is off at the last chapter. Back and forward keep their places and are off
/// while there is no settled position to skip from (a stall re-buffers; spec S12), so the row never re-flows.
struct PlayerTransportControls: View {
    let observer: PlayerObserver

    /// Counts deliberate transport taps so the haptic fires on the tap, never on a state change
    /// arriving from elsewhere (a remote command, the lock screen). Mirrors `MiniPlayerBar`.
    @State private var transportTapCount = 0
    @State private var playPauseTapCount = 0
    /// The state the tap *moves to*, captured at tap time. Reading `isPlaybackActive` in the
    /// modifier would race the Kotlin StateFlow's trip through FlowBridge, so the verb could
    /// describe the state we just left. Mirrors Compose's `haptics.toggle(on = !isPlaying)`.
    @State private var playPauseWillBeActive = false

    @ScaledMetric(relativeTo: .title) private var playButtonSize: CGFloat = 76

    private var hasNextChapter: Bool { observer.canSkipToNextChapter }
    private var hasPreviousChapter: Bool { observer.canSkipToPreviousChapter }
    private var canSkip: Bool { observer.showsSkipBack }

    var body: some View {
        HStack(spacing: 0) {
            transportButton(
                systemImage: "backward.end.fill",
                font: .title3,
                label: String(localized: "player.previous_chapter"),
                hint: String(localized: "player.np_previous_chapter_hint"),
                isEnabled: hasPreviousChapter
            ) {
                observer.previousChapter()
            }

            Spacer(minLength: 4)

            transportButton(
                systemImage: PlayerGlyphs.skipBackward(seconds: observer.skipBackwardSec),
                font: .title2,
                label: String(format: String(localized: "player.skip_backward"), "\(observer.skipBackwardSec)"),
                isEnabled: canSkip
            ) {
                observer.skipBackward()
            }

            Spacer(minLength: 4)

            playPauseButton

            Spacer(minLength: 4)

            transportButton(
                systemImage: PlayerGlyphs.skipForward(seconds: observer.skipForwardSec),
                font: .title2,
                label: String(format: String(localized: "player.skip_forward"), "\(observer.skipForwardSec)"),
                isEnabled: canSkip
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
                observer.nextChapter()
            }
        }
        .haptic(.press, trigger: transportTapCount)
        .haptic(playPauseWillBeActive ? .toggleOn : .toggleOff, trigger: playPauseTapCount)
    }

    private func transportButton(
        systemImage: String,
        font: Font,
        label: String,
        hint: String? = nil,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            transportTapCount += 1
            action()
        } label: {
            Image(systemName: systemImage)
                .font(font)
                .foregroundStyle(isEnabled ? Color.primary : Color.luOnWashTertiary)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityLabel(label)
        .accessibilityHint(hint ?? "")
    }

    /// The main control as a coral glyph with no fill (HIG, Playing audio: play and pause are the system's large glyphs in
    /// the tint), in a 76pt target. While busy it is a spinner, and a tap still pauses a stall.
    private var playPauseButton: some View {
        Button {
            playPauseWillBeActive = !observer.isPlaybackActive
            playPauseTapCount += 1
            observer.primaryAction()
        } label: {
            ZStack {
                if observer.primary == .busy {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.large)
                        .tint(Color.luTint)
                } else {
                    Image(systemName: observer.isPlaybackActive ? "pause.fill" : "play.fill")
                        .font(.system(size: 52)) // decorative fixed size: the glyph fills its 76pt target
                        .foregroundStyle(Color.luTint)
                        .contentTransition(.symbolEffect(.replace.downUp))
                }
            }
            .frame(width: playButtonSize, height: playButtonSize)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playLabel)
    }

    private var playLabel: String {
        if observer.primary == .busy { return String(localized: "player.mini_busy_a11y") }
        return String(localized: observer.isPlaybackActive ? "player.pause" : "player.play")
    }
}

// MARK: - Secondary controls

/// Speed · Sleep · Chapters · AirPlay, each a 44pt control over a caption (Volume Boost lives in More, §5.5.7). Each control
/// names itself to VoiceOver (label + value), so the visible caption is hidden from it rather than
/// read twice. At accessibility text sizes the row becomes a list of control-beside-caption rows,
/// which the player column scrolls (HIG, Typography: "consider using a stacked layout").
struct PlayerSecondaryControls: View {
    let observer: PlayerObserver
    let showsChaptersControl: Bool
    let onShowChapters: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// Speed and Sleep are sheets, not menus: their values are adjusted, not picked once (HIG, Sheets; plan C10).
    @State private var showsSpeed = false
    @State private var showsSleep = false

    private var isStacked: Bool { dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        let layout = isStacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 6))
        layout {
            item(caption: String(localized: "player.speed")) {
                Button { showsSpeed = true } label: {
                    pill(PlaybackRates.format(observer.playbackSpeed))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "player.speed"))
                .accessibilityValue(PlaybackRates.format(observer.playbackSpeed))
            }

            item(caption: String(localized: "player.sleep")) {
                Button { showsSleep = true } label: {
                    if observer.sleepTimerActive {
                        sleepCountdown
                    } else {
                        glyph("moon.zzz")
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "player.sleep_timer"))
                // The time left while a timer runs, "Off" otherwise: the moon's fill alone would be state by shape.
                .accessibilityValue(
                    observer.sleepTimerActive ? observer.sleepTimerLabel : String(localized: "player.sleep_off"))
            }

            if showsChaptersControl {
                item(caption: String(localized: "player.chapters")) {
                    Button(action: onShowChapters) { glyph("list.bullet") }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(localized: "player.chapters"))
                }
            }

            // AirPlay's system route picker voices itself; its caption stays readable beside it. While the audio plays
            // elsewhere the caption names the device (HIG, AirPlay: keep the system control as it is; plan C13).
            item(caption: observer.castDeviceName ?? String(localized: "player.airplay"), captionIsSpoken: true) {
                RoutePickerView(
                    tint: Color(.label), activeTint: .luTint,
                    playingOn: observer.castDeviceName.map {
                        String(format: String(localized: "player.np_status_playing_on"), $0)
                    })
                    .frame(width: 44, height: 44)
            }

        }
        .sheet(isPresented: $showsSpeed) { SpeedSheet(observer: observer) }
        .sheet(isPresented: $showsSleep) { SleepSheet(observer: observer) }
        .reportsPlayerSheet(showsSpeed || showsSleep)
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
                .foregroundStyle(Color.luOnWashSecondary)
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

    private func glyph(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .font(.title3)
            .foregroundStyle(.primary)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }

    /// An armed timer counts down in the sleep gold (§3.9) instead of showing a filled moon: the time left is the
    /// state, not the glyph's fill. End of chapter counts down to the chapter's end at the listening speed.
    private var sleepCountdown: some View {
        Text(observer.sleepTimerLabel)
            .monospacedDigit()
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.luOnSleepGold)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, Spacing.xxs)
            .frame(minHeight: 28)
            .background(Color.luSleepGold, in: Capsule())
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }
}
