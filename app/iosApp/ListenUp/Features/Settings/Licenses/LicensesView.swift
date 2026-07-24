import SwiftUI

/// Open-source acknowledgements screen.
///
/// Shows a summary card with a proportional distribution meter, a searchable list of
/// every open-source library the app ships, and a footer. Tapping a row pushes
/// `LicenseDetailView` for the full license text. Layout is width-responsive (iosApp rule 12):
/// on compact (iPhone / narrow split view) the summary card stacks above the library list;
/// on regular width (iPad) the overview/meter panel sits beside the library list in a two-pane
/// HStack, per the `LicensesPad` mockup.
struct LicensesView: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var searchText = ""

    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

    private var filteredLibraries: [IosLicense] {
        guard !searchText.isEmpty else { return LicenseData.all }
        return LicenseData.all.filter {
            $0.name.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        Group {
            if isRegularWidth {
                regularBody
            } else {
                compactBody
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "settings.open_source_licenses"))
        .navigationBarTitleDisplayMode(.large)
        .searchable(
            text: $searchText,
            placement: .navigationBarDrawer(displayMode: .automatic),
            prompt: Text(String(localized: "licenses.search_placeholder"))
        )
    }

    // MARK: - Regular layout (iPad / wide split view)

    /// Two-pane: left = overview/meter panel, right = scrollable library list.
    private var regularBody: some View {
        HStack(alignment: .top, spacing: 0) {
            // Left pane — overview panel (sticky alongside the scrolling list)
            overviewPanel
                .frame(width: 280)
                .padding(.leading, 32)
                .padding(.trailing, 24)
                .padding(.top, 16)

            Divider()

            // Right pane — library list
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    sectionHeader(String(localized: "licenses.section_libraries"))

                    libraryList

                    footerNote
                        .padding(.top, 12)
                        .padding(.bottom, 24)
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }
        }
    }

    // MARK: - Compact layout (iPhone / narrow split view)

    private var compactBody: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                summaryCard
                    .padding(.bottom, 18)

                sectionHeader(String(localized: "licenses.section_libraries"))

                libraryList

                footerNote
                    .padding(.top, 12)
                    .padding(.bottom, 24)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .readableWidth(720)
        }
    }

    // MARK: - Overview panel (regular-width left pane)

    /// The standalone overview panel used in the wide layout — count, meter, legend, and a
    /// small contextual note. Kept non-scrolling so it stays anchored while the list scrolls.
    private var overviewPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Count + label
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("\(LicenseData.all.count)")
                    .font(.largeTitle.weight(.bold))
                    .foregroundStyle(.primary)
                Text(String(localized: "licenses.count_suffix"))
                    .font(.headline)
                    .foregroundStyle(Color.luLabel2)
            }

            DistributionMeter()

            legendRow

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Summary card (compact layout)

    private var summaryCard: some View {
        FieldGroup([0], id: \.self) { _ in
            VStack(alignment: .leading, spacing: 0) {
                // Count + label
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("\(LicenseData.all.count)")
                        .font(.largeTitle.weight(.bold))
                        .foregroundStyle(.primary)
                    Text(String(localized: "licenses.count_suffix"))
                        .font(.headline)
                        .foregroundStyle(Color.luLabel2)
                }

                DistributionMeter()
                    .padding(.top, 14)

                legendRow
                    .padding(.top, 13)
            }
            .padding(16)
        }
    }

    // MARK: - Distribution legend

    private var legendRow: some View {
        let distribution = licenseDistribution()
        return HStack(spacing: 16) {
            ForEach(distribution.prefix(4), id: \.spdxId) { entry in
                HStack(spacing: 7) {
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(LicenseData.licenseColor(entry.spdxId))
                        .frame(width: 10, height: 10)
                    Text(entry.spdxId)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text("\(entry.count)")
                        .font(.caption)
                        .foregroundStyle(Color.luLabel2)
                }
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: - Library list

    private var libraryList: some View {
        FieldGroup(filteredLibraries, separatorInset: 14) { lib in
            NavigationLink(value: LicenseDetailDestination(packageName: lib.name)) {
                libraryRow(lib)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private func libraryRow(_ lib: IosLicense) -> some View {
        HStack(spacing: 13) {
            VStack(alignment: .leading, spacing: 2) {
                Text(lib.name)
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text("v\(lib.version)")
                    .font(.caption.monospaced())
                    .foregroundStyle(Color.luLabel2)
            }
            Spacer(minLength: 8)
            LicenseChip(spdxId: lib.spdxId)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel3)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    // MARK: - Footer

    private var footerNote: some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: "doc.text")
                .font(.caption2)
                .foregroundStyle(Color.luLabel2)
                .padding(.top, 1)
            Text(String(localized: "licenses.footer"))
                .font(.footnote)
                .foregroundStyle(Color.luLabel2)
        }
        .padding(.horizontal, 4)
    }

    // MARK: - Section header

    @ViewBuilder
    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.luLabel2)
            .padding(.leading, 4)
            .padding(.bottom, 8)
    }

    // MARK: - Data helpers

    private struct DistributionEntry {
        let spdxId: String
        let count: Int
    }

    private func licenseDistribution() -> [DistributionEntry] {
        var counts: [String: Int] = [:]
        for lib in LicenseData.all {
            counts[lib.spdxId, default: 0] += 1
        }
        return counts
            .map { DistributionEntry(spdxId: $0.key, count: $0.value) }
            .sorted { $0.count > $1.count }
    }
}

// MARK: - Distribution Meter

/// Proportional colour-bar showing the breakdown of license families.
private struct DistributionMeter: View {
    @Environment(\.displayScale) private var displayScale

    private struct Segment {
        let spdxId: String
        let fraction: CGFloat
    }

    private var segments: [Segment] {
        let total = CGFloat(LicenseData.all.count)
        var counts: [String: Int] = [:]
        for lib in LicenseData.all { counts[lib.spdxId, default: 0] += 1 }
        return counts
            .map { Segment(spdxId: $0.key, fraction: CGFloat($0.value) / total) }
            .sorted { $0.fraction > $1.fraction }
    }

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 2) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(LicenseData.licenseColor(seg.spdxId))
                        .frame(width: max(4, geo.size.width * seg.fraction - 2))
                }
            }
        }
        .frame(height: 11)
        .clipShape(Capsule())
    }
}

// MARK: - License Chip

/// Small coloured pill showing a SPDX identifier (e.g. "Apache-2.0", "MIT").
struct LicenseChip: View {
    let spdxId: String

    var body: some View {
        Text(spdxId)
            .font(.caption2.weight(.bold))
            .foregroundStyle(LicenseData.licenseColor(spdxId))
            .padding(.horizontal, 11)
            .padding(.vertical, 4)
            .background(
                Capsule().fill(LicenseData.licenseColor(spdxId).opacity(0.14))
            )
            .fixedSize()
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        LicensesView()
    }
}
