import SwiftUI

/// A classification field whose value is chips: genres, moods or tags. Each source's run is headed by
/// its "from {source}" chip, and every chip toggles on its own; the field's check turns them all on or off.
/// For VoiceOver each chip is its own stop, and a chip a fallback provider supplied says so itself
/// ("Fantasy, from Hardcover"), so the provenance #1542 shows travels with the value it describes.
struct MetadataChipField: View {
    let systemImage: String
    let label: String
    let runs: [MetadataSourceRun]
    let onToggleAll: () -> Void
    let onToggle: (String) -> Void

    var body: some View {
        MetadataFieldRow(
            systemImage: systemImage,
            label: label,
            isOn: runs.contains { run in run.items.contains(where: \.isSelected) },
            containsControls: true,
            onToggle: onToggleAll
        ) {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(runs) { run in
                    MetadataSourceChip(source: run.source)
                    FlowLayout(spacing: 8) {
                        ForEach(run.items) { item in
                            MetadataGenreChip(label: item.label, isOn: item.isSelected, source: run.source) {
                                onToggle(item.id)
                            }
                        }
                    }
                }
            }
            .padding(.top, Spacing.xxs)
        }
    }
}

/// The select step's summary under the matched edition: how many fields are selected, and — at the
/// accessibility sizes, where the apply tray keeps only its button — which providers the metadata was
/// merged from.
struct MetadataSelectionSummary: View {
    let selectedCount: Int
    let totalCount: Int
    var contributingSources: [String] = []
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(format: String(localized: "metadata.fields_selected"), selectedCount, totalCount))
                .textCase(.uppercase)
            if dynamicTypeSize.isAccessibilitySize, let merged = MetadataApplyTray.mergedFromText(contributingSources) {
                Text(merged)
            }
        }
        .padding(.top, Spacing.xs)
    }
}
