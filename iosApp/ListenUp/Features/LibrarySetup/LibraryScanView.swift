import SwiftUI

/// Full-screen "Building your library" gate, shown while the **initial** population scan runs
/// instead of mounting an empty shell — so a first-run user watches their library fill in
/// rather than staring at a blank app wondering whether anything is happening.
///
/// Mirrors Android's `LibraryScanScreen`, reimagined for iOS per the Cupertino setup design:
/// a circular progress ring (percentage + ETA), grouped Books/Authors/Hours stat rows, and the
/// live file line. Driven by the native ``ScanProgress`` mapped at the observer boundary;
/// a `nil` progress renders the brief indeterminate "finishing up" import tail.
///
/// Like Android, this hard-gates the shell (no "browse while scanning"): mounting the library
/// mid-import would decode a flood of covers under the catch-up reconcile. The gate clears
/// automatically the instant the books land in Room and readiness flips to `ready`.
struct LibraryScanView: View {
    let progress: ScanProgress?
    /// True once the scan has stalled long enough to offer the never-stranded escape below.
    var stalled: Bool = false
    /// Enter the partial library now (only surfaced when `stalled`).
    var onContinue: () -> Void = {}

    @Environment(\.horizontalSizeClass) private var sizeClass

    private var isWide: Bool { sizeClass == .regular }
    private var ringSize: CGFloat { isWide ? 184 : 168 }

    var body: some View {
        ZStack {
            Color(.systemGroupedBackground).ignoresSafeArea()
            ScrollView {
                content
                    .frame(maxWidth: isWide ? 460 : 360)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 48)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    // MARK: - Content

    private var content: some View {
        VStack(spacing: 0) {
            TimelineView(.periodic(from: .now, by: 1.0)) { context in
                ring(now: context.date)
            }

            Text(String(localized: "library_setup.building_title"))
                .font(isWide ? .largeTitle.bold() : .title.bold())
                .multilineTextAlignment(.center)
                .padding(.top, 22)

            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 6)

            statsCard
                .padding(.top, 26)

            if let file = progress?.currentFile, !file.isEmpty {
                currentFileLine(file)
                    .padding(.top, 18)
            }

            if stalled {
                stalledEscape
                    .padding(.top, 28)
            }
        }
    }

    // MARK: - Stalled escape

    /// Never-stranded escape shown when the scan has gone quiet: an honest message, a primary
    /// "Continue" into the partial library, and a hint pointing at the manual rescan in Settings.
    /// Mirrors Android's `StalledEscape`.
    private var stalledEscape: some View {
        VStack(spacing: 12) {
            Text(String(localized: "library_scan.stalled_message"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(action: onContinue) {
                Text(String(localized: "library_scan.continue"))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Text(String(localized: "library_scan.stalled_settings_hint"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    // MARK: - Ring

    @ViewBuilder
    private func ring(now: Date) -> some View {
        ZStack {
            Circle().stroke(Color(.tertiarySystemFill), lineWidth: 13)

            if let fraction = progress?.fraction {
                Circle()
                    .trim(from: 0, to: CGFloat(fraction))
                    .stroke(
                        Color.listenUpOrange,
                        style: StrokeStyle(lineWidth: 13, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.3), value: fraction)
                VStack(spacing: 2) {
                    Text("\(Int((fraction * 100).rounded()))%")
                        .font(.system(size: isWide ? 38 : 34, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(.primary)
                        .contentTransition(.numericText())
                    if let eta = etaText(now: now, fraction: fraction) {
                        Text(eta)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                SpinningArc(lineWidth: 13)
                if let phase = progress?.phaseDisplay {
                    Text(phase)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .frame(width: ringSize, height: ringSize)
        .accessibilityElement()
        .accessibilityLabel(ringAccessibilityLabel)
    }

    // MARK: - Stats

    private var statsCard: some View {
        VStack(spacing: 0) {
            statRow(icon: "book", label: String(localized: "library_scan.books"), value: progress?.books ?? 0)
            rowDivider
            statRow(icon: "person", label: String(localized: "library_scan.authors"), value: progress?.authors ?? 0)
            rowDivider
            statRow(icon: "clock", label: String(localized: "library_scan.hours"), value: progress?.hours ?? 0)
        }
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color(.secondarySystemGroupedBackground))
        )
    }

    private var rowDivider: some View {
        Divider().padding(.leading, 57)
    }

    private func statRow(icon: String, label: String, value: Int) -> some View {
        HStack(spacing: 13) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.listenUpOrange.opacity(0.14))
                .frame(width: 30, height: 30)
                .overlay {
                    Image(systemName: icon)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.listenUpOrange)
                }
            Text(label)
                .font(.body)
                .foregroundStyle(.primary)
            Spacer(minLength: 8)
            Text(value.formatted())
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(.primary)
                .contentTransition(.numericText())
                .animation(.default, value: value)
        }
        .frame(minHeight: 54)
        .padding(.horizontal, 14)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Current file

    private func currentFileLine(_ file: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "waveform")
                .font(.footnote)
                .foregroundStyle(Color.listenUpOrange)
            Text(file)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 4)
        .accessibilityHidden(true)
    }

    // MARK: - Derived copy

    private var subtitle: String {
        guard let progress else {
            return String(localized: "library_scan.subtitle_finishing")
        }
        if progress.isPersisting {
            return String(localized: "library_scan.subtitle_persisting")
        }
        if progress.fraction == nil {
            return String(localized: "library_scan.subtitle_scanning")
        }
        return String(
            format: String(localized: "library_scan.subtitle_analyzing"),
            progress.filesDone,
            progress.filesTotal
        )
    }

    /// Client-side ETA: extrapolate total time from elapsed ÷ fraction. Suppressed during the
    /// PERSISTING phase (its bar restarts at 0 after analysis already elapsed, so there's no
    /// meaningful per-scan time), when the start time is unknown, or at very low progress.
    private func etaText(now: Date, fraction: Double) -> String? {
        guard let progress, !progress.isPersisting, progress.startedAtMs > 0, fraction > 0.02 else {
            return nil
        }
        let elapsedMs = now.timeIntervalSince1970 * 1000 - Double(progress.startedAtMs)
        guard elapsedMs > 0 else { return nil }
        let totalMs = elapsedMs / fraction
        let remainingMin = Int(((totalMs - elapsedMs) / 60_000).rounded(.up))
        guard remainingMin >= 1 else { return nil }
        return String(format: String(localized: "library_scan.eta"), remainingMin)
    }

    private var ringAccessibilityLabel: String {
        if let fraction = progress?.fraction {
            return String(
                format: String(localized: "library_scan.progress_a11y"),
                Int((fraction * 100).rounded())
            )
        }
        return progress?.phaseDisplay ?? String(localized: "library_scan.subtitle_finishing")
    }
}

// MARK: - Spinning arc (indeterminate)

/// A continuously rotating arc for the indeterminate discovery / finishing-up phases, where no
/// fraction is known. Honors Reduce Motion by holding a static arc instead of spinning.
private struct SpinningArc: View {
    let lineWidth: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var spinning = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.22)
            .stroke(
                Color.listenUpOrange,
                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
            )
            .rotationEffect(.degrees(spinning ? 360 : 0))
            .animation(
                reduceMotion ? nil : .linear(duration: 1).repeatForever(autoreverses: false),
                value: spinning
            )
            .onAppear { if !reduceMotion { spinning = true } }
    }
}

// MARK: - Preview

#Preview("Scanning — determinate") {
    LibraryScanView(progress: ScanProgress(
        phaseDisplay: "Analyzing",
        isPersisting: false,
        filesDone: 636,
        filesTotal: 1647,
        fraction: 0.39,
        books: 178,
        authors: 22,
        hours: 267,
        currentFile: "Sanderson/Mistborn/01 — The Final Empire.m4b",
        savingLabel: "Saving 178 of 412",
        startedAtMs: 0
    ))
}

#Preview("Scanning — finishing up") {
    LibraryScanView(progress: nil)
}
