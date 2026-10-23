import SwiftUI
@preconcurrency import Shared

/// The Speed sheet's rules, each the shared one (player spec §5.5.1, PS1–PS5), so iOS can't disagree with Android or the
/// web about where a step lands or which preset is on.
enum SpeedSheetModel {
    /// One 0.05× step, counted in the shared whole hundredths: thirty presses never drift off a rung.
    static func step(_ speed: Float, up: Bool) -> Float {
        ExportedKotlinPackages.com.calypsan.listenup.client.presentation.player.stepPlaybackSpeed(speed: speed, up: up)
    }

    /// A dragged value, snapped to the nearest 0.05×.
    static func snapped(_ speed: Float) -> Float {
        ExportedKotlinPackages.com.calypsan.listenup.client.presentation.player.snapPlaybackSpeed(speed: speed)
    }

    /// Whether `preset` is the speed playing, within the shared tolerance (a stored 1.5 comes back as 1.4999999).
    static func isSelected(_ preset: Float, speed: Float) -> Bool {
        ExportedKotlinPackages.com.calypsan.listenup.client.presentation.player.isSamePlaybackSpeed(a: preset, b: speed)
    }

    /// Reset is offered only away from the default: at it, there is nothing to go back to.
    static func showsReset(speed: Float, defaultSpeed: Float) -> Bool {
        !isSelected(defaultSpeed, speed: speed)
    }

    /// "1.25 times": the slider's value as VoiceOver speaks it.
    static func spokenValue(_ speed: Float) -> String {
        String(format: String(localized: "player.sheet_speed_times_ios"), PlaybackRates.number(speed))
    }

    /// "32 hr, 25 min left at this speed": the book's remainder in wall time, the sheet's one exception to content
    /// time (S10).
    static func leftLine(ms: Int64) -> String {
        String(format: String(localized: "player.sheet_left_at_speed"), DurationFormatting.abbreviated(ms: ms))
    }
}

/// What the Speed sheet does; the observer supplies each.
struct SpeedSheetActions {
    var setSpeed: (Float) -> Void
    var step: (_ up: Bool) -> Void
    var reset: () -> Void
}

/// The Speed sheet's content (§5.5.1, iOS column; the `Sheets-iPhone` board): the readout and its wall-time line, a fine
/// `Slider` between − and +, the nine presets, Reset and the note. It stays open while the reader tries speeds (HIG,
/// Sheets: values here are adjusted, not picked once).
struct SpeedSheetContent: View {
    let speed: Float
    let defaultSpeed: Float
    let leftAtSpeedMs: Int64
    let actions: SpeedSheetActions

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// − and + keep a 44pt target at every size and grow with the text (HIG, Accessibility).
    @ScaledMetric(relativeTo: .body) private var stepSide: CGFloat = 44

    private var title: String { String(localized: "player.sheet_speed_title_ios") }

    /// "Reset to 1× (Your Default)".
    static func resetTitle(_ defaultSpeed: Float) -> String {
        String(format: String(localized: "player.sheet_reset_speed_ios"), PlaybackRates.format(defaultSpeed))
    }

    var body: some View {
        VStack(spacing: Spacing.l) {
            VStack(spacing: Spacing.xxs) {
                Text(PlaybackRates.format(speed))
                    .font(.largeTitle.weight(.bold))
                    .monospacedDigit()
                    // The slider speaks the same value; the readout would say it twice.
                    .accessibilityHidden(true)
                Text(SpeedSheetModel.leftLine(ms: leftAtSpeedMs))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: Spacing.s) {
                stepButton(up: false)
                slider
                stepButton(up: true)
            }

            presets

            if SpeedSheetModel.showsReset(speed: speed, defaultSpeed: defaultSpeed) {
                Button(Self.resetTitle(defaultSpeed)) { actions.reset() }
                .buttonStyle(.borderless)
                .frame(minHeight: TapTarget.minimum)
            }

            Text(String(localized: "player.sheet_this_book_only"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
    }

    /// HIG, Sliders: a continuous choice across a range, here 0.5…3× in 0.05× steps. VoiceOver adjusts it in the same
    /// shared steps as − and +.
    private var slider: some View {
        Slider(
            value: Binding(
                get: { speed },
                set: { value in
                    let next = SpeedSheetModel.snapped(value)
                    if !SpeedSheetModel.isSelected(next, speed: speed) { actions.setSpeed(next) }
                }
            ),
            in: 0.5...3.0,
            step: 0.05
        ) {
            Text(title)
        }
        .accessibilityLabel(title)
        .accessibilityValue(SpeedSheetModel.spokenValue(speed))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: actions.step(true)
            case .decrement: actions.step(false)
            @unknown default: break
            }
        }
    }

    private func stepButton(up: Bool) -> some View {
        let atLimit = up ? speed >= 3.0 - 0.001 : speed <= 0.5 + 0.001
        return Button { actions.step(up) } label: {
            Image(systemName: up ? "plus" : "minus")
                .font(.body.weight(.semibold))
                .foregroundStyle(atLimit ? Color.secondary : Color.primary)
                .frame(width: max(stepSide, TapTarget.minimum), height: max(stepSide, TapTarget.minimum))
                .background(Color.luFill, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(atLimit)
        .accessibilityLabel(up ? String(localized: "player.sheet_faster") : String(localized: "player.sheet_slower"))
    }

    /// The nine presets in a grid of capsules: three across, two at accessibility sizes so "0.75×" never truncates
    /// (HIG, Typography). The one playing fills coral and is selected, so it isn't marked by colour alone.
    private var presets: some View {
        let columns = dynamicTypeSize.isAccessibilitySize ? 2 : 3
        let rates = PlaybackRates.catalogue
        let rows = stride(from: 0, to: rates.count, by: columns).map {
            Array(rates[$0..<min($0 + columns, rates.count)])
        }
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "player.sheet_speed_presets"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            Grid(horizontalSpacing: Spacing.s, verticalSpacing: Spacing.s) {
                ForEach(rows.indices, id: \.self) { row in
                    GridRow {
                        ForEach(rows[row], id: \.self) { preset in presetButton(preset) }
                    }
                }
            }
        }
    }

    private func presetButton(_ preset: Float) -> some View {
        let isSelected = SpeedSheetModel.isSelected(preset, speed: speed)
        return Button { actions.setSpeed(preset) } label: {
            Text(PlaybackRates.format(preset))
                .font(.subheadline.weight(isSelected ? .semibold : .medium))
                .monospacedDigit()
                .foregroundStyle(isSelected ? Color.luOnTint : Color.primary)
                .frame(maxWidth: .infinity, minHeight: TapTarget.minimum)
                .background(isSelected ? AnyShapeStyle(Color.luTint) : AnyShapeStyle(Color.luFill), in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// The Speed sheet over the shared player.
struct SpeedSheet: View {
    let observer: PlayerObserver

    var body: some View {
        PlayerSheetChrome(title: String(localized: "player.sheet_speed_title_ios")) {
            SpeedSheetContent(
                speed: observer.playbackSpeed,
                defaultSpeed: observer.defaultSpeed,
                leftAtSpeedMs: observer.bookRemainingAtSpeedMs,
                actions: SpeedSheetActions(
                    setSpeed: { observer.setSpeed($0) },
                    step: { observer.stepSpeed(up: $0) },
                    reset: { observer.resetSpeed() }
                )
            )
        }
        .playerSheetDetents()
    }
}
