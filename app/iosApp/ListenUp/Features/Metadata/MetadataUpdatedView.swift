import SwiftUI
import Shared

/// Step 4: confirmation. A success mark, "Metadata updated", and a small summary of what changed
/// (fields applied, chapters named, cover source). "Done" dismisses the whole wizard.
struct MetadataUpdatedView: View {
    let bookTitle: String
    let observer: MetadataMatchObserver
    let onDone: () -> Void

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 96))
                    .foregroundStyle(Color.luTint)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)

                Text(String(localized: "metadata.updated_title"))
                    .font(.largeTitle.weight(.bold))
                    .padding(.top, 22)

                Text(String(format: String(localized: "metadata.updated_subtitle"), bookTitle))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)

                summary
                    .padding(.top, 26)
            }
            .frame(maxWidth: 360)
            .padding(.horizontal, 18)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.luSurface)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 0) {
                Divider()
                PrimaryButton(title: String(localized: "common.done"), icon: "checkmark", action: onDone)
                    .padding(16)
                    .readableWidth(360)
            }
            .background(.bar)
        }
    }

    @ViewBuilder
    private var summary: some View {
        if let preview = lastPreview {
            FieldGroup([0], id: \.self) { _ in
                VStack(spacing: 0) {
                    summaryRow(
                        icon: "checkmark.circle",
                        label: String(localized: "metadata.updated_fields_applied"),
                        value: "\(preview.selectedCount)"
                    )
                    if case .available(let available) = preview.chapters, available.selectedCount > 0 {
                        Divider()
                        summaryRow(
                            icon: "waveform",
                            label: String(localized: "metadata.updated_chapters_named"),
                            value: "\(available.selectedCount)"
                        )
                    }
                    if preview.coverEnabled {
                        Divider()
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
            Text(value).font(.callout.weight(.medium)).foregroundStyle(Color.luLabel2)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}
