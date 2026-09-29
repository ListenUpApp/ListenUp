import SwiftUI
import Shared

/// Step 4: confirmation. A success mark, "Metadata updated", and a small summary of what changed
/// (fields applied, chapters named, cover source). "Done" dismisses the whole wizard.
struct MetadataUpdatedView: View {
    let bookTitle: String
    let observer: MetadataMatchObserver
    let onDone: () -> Void

    /// A grouped `List`: the success mark and headline on the plain background, and what changed as
    /// a section of rows beneath (HIG, Lists and tables).
    var body: some View {
        List {
            Section {
                VStack(spacing: 0) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 96)) // decorative fixed size
                        .foregroundStyle(Color.luTint)
                        .symbolRenderingMode(.hierarchical)
                        .accessibilityHidden(true)

                    Text(String(localized: "metadata.updated_title"))
                        .font(.largeTitle.weight(.bold))
                        .padding(.top, 22)

                    Text(String(format: String(localized: "metadata.updated_subtitle"), bookTitle))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 24)
                .listRowBackground(Color.clear)
            }
            summary
        }
        .listStyle(.insetGrouped)
        .readableListWidth(420)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaBar(edge: .bottom) {
            VStack(spacing: 0) {
                Divider()
                Button(action: onDone) {
                    ActionLabel(title: String(localized: "common.done"), systemImage: "checkmark")
                }
                .prominentAction()
                .padding(16)
                .readableWidth(360)
            }
        }
    }

    @ViewBuilder
    private var summary: some View {
        if let preview = lastPreview {
            Section {
                summaryRow(
                    icon: "checkmark.circle",
                    label: String(localized: "metadata.updated_fields_applied"),
                    value: "\(preview.selectedCount)"
                )
                if case .available(let available) = preview.chapters, available.selectedCount > 0 {
                    summaryRow(
                        icon: "waveform",
                        label: String(localized: "metadata.updated_chapters_named"),
                        value: "\(available.selectedCount)"
                    )
                }
                if preview.coverEnabled {
                    let source = String(localized: "metadata.audible_source")
                    summaryRow(
                        icon: "photo",
                        label: String(localized: "metadata.updated_cover_replaced"),
                        value: String(format: source, observer.region.displayName)
                    )
                }
            }
        }
    }

    private var lastPreview: MetadataPreview? {
        if case .preview(.ready(let preview)) = observer.phase { return preview }
        return nil
    }

    private func summaryRow(icon: String, label: String, value: String) -> some View {
        HStack(spacing: 13) {
            IconTile(systemImage: icon)
            Text(label).font(.callout).foregroundStyle(.primary)
            Spacer()
            Text(value).font(.callout.weight(.medium)).foregroundStyle(Color.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}
