import SwiftUI

/// The "building your library" step of first-run setup, shown after a library is created.
///
/// The scan runs server-side and streams live progress over the shared
/// ``LibrarySetupViewModelWrapper/scan`` channel. This screen is deliberately
/// **non-blocking**: it reflects progress as it arrives but never gates the user — the
/// primary action lets them start browsing while the scan keeps working in the background.
///
/// Progress is shown honestly. When the scan reports totals we render a determinate bar,
/// a `666 / 1,647 files` counter, and a percentage; before the first tally arrives we show
/// an indeterminate spinner and "Starting scan…" rather than fabricating numbers. There is
/// no ETA because the source doesn't expose one.
///
/// The view owns no business logic — it binds the same shared wrapper instance threaded
/// through ``ChooseFoldersView`` by the setup coordinator (Task 5).
struct BuildingLibraryView: View {

    /// The shared wrapper, owned by the coordinator. Same instance as `ChooseFoldersView`.
    @Bindable var viewModel: LibrarySetupViewModelWrapper

    var body: some View {
        AuthScaffold {
            header
            progressBlock
            currentFileLine
            statRow
        } footer: {
            AuthPrimaryButton(title: String(localized: "library_setup.browse_while_building")) {
                viewModel.finish()
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "library_setup.building_title"))
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(.primary)
            Text(String(localized: "library_setup.building_subtitle"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Progress block

    /// Determinate when totals are known, indeterminate ("Starting scan…") before the first
    /// tally. The whole block reads as a single VoiceOver element so progress isn't announced
    /// as a stream of noisy nodes.
    @ViewBuilder
    private var progressBlock: some View {
        AuthFieldGroup {
            VStack(alignment: .leading, spacing: 12) {
                if let fraction = viewModel.scan?.fraction {
                    // Totals known — honest determinate bar with a percentage.
                    HStack(alignment: .firstTextBaseline) {
                        Text(viewModel.scan?.filesLabel ?? "")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                        Spacer(minLength: 8)
                        Text("\(Int((fraction * 100).rounded()))%")
                            .font(.subheadline.weight(.semibold).monospacedDigit())
                            .foregroundStyle(Color.listenUpOrange)
                    }
                    ProgressView(value: fraction)
                        .tint(Color.listenUpOrange)
                } else {
                    // Walking phase (no totals yet) — indeterminate, never a fake 0%.
                    HStack(spacing: 10) {
                        ProgressView().controlSize(.small)
                        Text(String(localized: "library_setup.starting_scan"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Spacer()
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(progressAccessibilityLabel)
    }

    private var progressAccessibilityLabel: String {
        guard let scan = viewModel.scan, let fraction = scan.fraction else {
            return String(localized: "library_setup.building_starting_a11y")
        }
        return String(
            format: String(localized: "library_setup.building_progress_a11y"),
            Int((fraction * 100).rounded()),
            scan.books,
            scan.authors,
            scan.hours
        )
    }

    // MARK: - Live current-file line

    /// The file the scanner is reading right now, in a single-line monospaced style that
    /// truncates from the middle so both the parent folder and the filename stay legible.
    @ViewBuilder
    private var currentFileLine: some View {
        if let currentFile = viewModel.scan?.currentFile {
            Text(currentFile)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityHidden(true)
        }
    }

    // MARK: - Stat tiles

    private var statRow: some View {
        HStack(spacing: 12) {
            ScanStatTile(
                value: viewModel.scan?.books ?? 0,
                label: String(localized: "library_setup.stat_books")
            )
            ScanStatTile(
                value: viewModel.scan?.authors ?? 0,
                label: String(localized: "library_setup.stat_authors")
            )
            ScanStatTile(
                value: viewModel.scan?.hours ?? 0,
                label: String(localized: "library_setup.stat_hours")
            )
        }
        // Folded into the single progress element above for VoiceOver.
        .accessibilityHidden(true)
    }
}

// MARK: - Stat tile

/// A small card showing one live scan tally (e.g. `167` / `BOOKS`). The number animates
/// with a numeric ticker as it climbs — disabled under Reduce Motion, where it snaps.
private struct ScanStatTile: View {
    let value: Int
    let label: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 4) {
            Text("\(value)")
                .font(.title2.weight(.bold).monospacedDigit())
                .foregroundStyle(.primary)
                .contentTransition(reduceMotion ? .identity : .numericText())
                .animation(reduceMotion ? nil : .default, value: value)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label.uppercased())
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .padding(.horizontal, 8)
        .background(
            RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous)
                .fill(Color(.secondarySystemGroupedBackground))
        )
    }
}

// MARK: - Preview

#Preview("Building Library") {
    BuildingLibraryView(
        viewModel: LibrarySetupViewModelWrapper(
            viewModel: Dependencies.shared.librarySetupViewModel,
            syncRepository: Dependencies.shared.syncRepository
        )
    )
}
