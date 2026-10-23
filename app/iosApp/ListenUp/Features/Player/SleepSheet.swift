import SwiftUI
@preconcurrency import Shared

/// What the Sleep sheet does; the observer supplies each.
struct SleepSheetActions {
    var endOfChapter: () -> Void
    var minutes: (Int) -> Void
    var start: (Int) -> Void
    var extend: (Int) -> Void
    var turnOff: () -> Void
    var keepListening: () -> Void
}

/// The Sleep sheet's content (§5.5.2, iOS column; the `Sheets-iPhone` board).
///
/// Choosing is a list of plain buttons (HIG, Lists: an inset grouped list of actions), End of Chapter first and largest,
/// then the durations, then a custom time with − / + and Start. Running is a gold ring around the countdown, the time it
/// pauses at, the add-time capsules and Turn Off Timer, which keeps the sheet open and returns to the choices (S13).
/// The fade is the gold card with Keep Listening.
struct SleepSheetContent: View {
    let model: SleepSheetModel
    let actions: SleepSheetActions

    @State private var customMinutes = Int(SleepTimerChoices.shared.CUSTOM_DEFAULT_MINUTES)
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .largeTitle) private var ringSide: CGFloat = 200
    @ScaledMetric(relativeTo: .body) private var stepSide: CGFloat = 44

    var body: some View {
        switch model.mode {
        case .choosing(let endOfChapter):
            choosing(endOfChapter: endOfChapter)
        case let .running(countdown, pausesAt, meter, canExtend, chapterLine):
            ScrollView {
                running(countdown: countdown, pausesAt: pausesAt, meter: meter, canExtend: canExtend,
                        chapterLine: chapterLine)
            }
            .scrollBounceBehavior(.basedOnSize)
        case .fading:
            ScrollView {
                SleepFadeCard(onKeepListening: actions.keepListening)
                    .padding(Spacing.l)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    // MARK: - Choosing

    private func choosing(endOfChapter: String?) -> some View {
        List {
            Section {
                if let endOfChapter {
                    Button(action: actions.endOfChapter) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(localized: "player.sheet_end_of_chapter_ios"))
                                .font(.headline)
                            Text(endOfChapter)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, minHeight: TapTarget.minimum, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                }
                ForEach(SleepTimerOption.durationMinutes, id: \.self) { minutes in
                    Button(SleepTimerOption.formatDuration(minutes)) { actions.minutes(minutes) }
                        .frame(minHeight: TapTarget.minimum)
                }
            }
            Section {
                customRow
                Button(String(localized: "player.sheet_start")) { actions.start(customMinutes) }
                    .frame(minHeight: TapTarget.minimum)
            } header: {
                Text(String(localized: "player.sheet_custom"))
            } footer: {
                Text(String(localized: "player.sheet_fades_out"))
            }
        }
        .listStyle(.insetGrouped)
    }

    /// The custom time: its value, then − and + in 44pt targets, stepping the shared five-minute grid within 5...720
    /// (HIG, Steppers; HIG, Accessibility for the target: the system stepper's segments are 32pt tall).
    private var customRow: some View {
        let minutesText = String(format: String(localized: "player.sheet_custom_minutes"), customMinutes)
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xs))
            : AnyLayout(HStackLayout(spacing: Spacing.s))
        return layout {
            Text(minutesText)
                .font(.body.weight(.semibold))
                .monospacedDigit()
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 0) }
            HStack(spacing: Spacing.s) {
                customStep(up: false)
                customStep(up: true)
            }
        }
    }

    private func customStep(up: Bool) -> some View {
        let next = Int(SleepTimerChoices.shared.stepCustom(minutes: Int32(customMinutes), up: up))
        return Button { customMinutes = next } label: {
            Image(systemName: up ? "plus" : "minus")
                .font(.body.weight(.semibold))
                .frame(width: max(stepSide, TapTarget.minimum), height: max(stepSide, TapTarget.minimum))
                .background(Color.luFill, in: Circle())
                .contentShape(Circle())
        }
        // Borderless, so the row's two buttons stay two targets instead of the list making the row one.
        .buttonStyle(.borderless)
        .disabled(next == customMinutes)
        .accessibilityLabel(
            up ? String(localized: "player.sheet_custom_more") : String(localized: "player.sheet_custom_less"))
    }

    // MARK: - Running

    private func running(
        countdown: String, pausesAt: String, meter: Double, canExtend: Bool, chapterLine: String?
    ) -> some View {
        VStack(spacing: Spacing.l) {
            ZStack {
                Circle()
                    .stroke(Color.luSleepGold, lineWidth: 8)
                Circle()
                    .trim(from: 0, to: meter)
                    .stroke(Color.luSleepGoldMeter, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : .linear(duration: 1), value: meter)
                // A timer, read when asked; never announced as it ticks (spec §8: not a live region).
                Text(countdown)
                    .font(.largeTitle.weight(.bold))
                    .monospacedDigit()
                    .padding(Spacing.m)
            }
            // The ring grows with the countdown, but never past what a phone's width can hold.
            .frame(width: min(ringSide, 280), height: min(ringSide, 280))

            VStack(spacing: Spacing.xxs) {
                Text(pausesAt)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let chapterLine {
                    Text(chapterLine)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .multilineTextAlignment(.center)

            if canExtend { extendRow }

            Button(role: .destructive) { actions.turnOff() } label: {
                Text(String(localized: "player.sheet_turn_off_timer_ios")).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .buttonBorderShape(.capsule)
        }
        .frame(maxWidth: .infinity)
        .padding(Spacing.l)
    }

    /// +5 / +10 / +15 as capsules, side by side, or stacked when they don't fit.
    private var extendRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.s) { extendButtons }
            VStack(spacing: Spacing.s) { extendButtons }
        }
    }

    private var extendButtons: some View {
        ForEach(SleepTimerOption.extensionMinutes, id: \.self) { minutes in
            Button(String(format: String(localized: "player.extend_minutes"), minutes)) { actions.extend(minutes) }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .buttonBorderShape(.capsule)
        }
    }
}

/// The sleep fade (§5.6, `States-iPhone`'s "Sleep timer, fading out"): the gold card, a moon, "Fading Out…", why, and
/// one button, Keep Listening, which cancels the timer and brings the volume back. Shared by Now Playing's state card
/// and the Sleep sheet, so the two can't drift.
struct SleepFadeCard: View {
    let onKeepListening: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                Image(systemName: "moon.zzz.fill")
                    .accessibilityHidden(true)
                Text(String(localized: "player.sheet_fading_title_ios"))
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
            }
            Text(String(localized: "player.sheet_fading_body"))
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: onKeepListening) {
                Text(String(localized: "player.mini_keep_listening_ios"))
                    .foregroundStyle(Color.luSleepGold)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.luOnSleepGold)
            .controlSize(.large)
            .buttonBorderShape(.capsule)
            .padding(.top, Spacing.xxs)
        }
        .foregroundStyle(Color.luOnSleepGold)
        .padding(Spacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.luSleepGold, in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

/// The Sleep sheet over the shared player.
struct SleepSheet: View {
    let observer: PlayerObserver

    var body: some View {
        PlayerSheetChrome(title: String(localized: "player.sheet_sleep_title_ios"), scrolls: false) {
            SleepSheetContent(
                model: .make(snapshot: observer.snapshot, progress: observer.progress, now: .now),
                actions: SleepSheetActions(
                    endOfChapter: { observer.setSleepTimerEndOfChapter() },
                    minutes: { observer.setSleepTimer(minutes: $0) },
                    start: { observer.setSleepTimer(minutes: $0) },
                    extend: { observer.extendSleepTimer(minutes: $0) },
                    turnOff: { observer.cancelSleepTimer() },
                    keepListening: { observer.keepListening() }
                )
            )
        }
        // The choices never fit the medium detent, so the large one is always there to pull up to.
        .playerSheetDetents(alwaysLarge: true)
    }
}
