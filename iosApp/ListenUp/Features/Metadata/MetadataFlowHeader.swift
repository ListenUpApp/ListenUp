import SwiftUI

/// The large-title header for a metadata-wizard step: an optional coral "Match metadata" badge,
/// a bold large title, and an optional subtitle. Feature-local (the metadata flow's voice), but
/// shaped like the design's `FlowHeader`.
struct MetadataFlowHeader: View {
    var badge: String?
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let badge {
                HStack(spacing: 6) {
                    Image(systemName: "sparkles").font(.caption.weight(.semibold))
                    Text(badge).font(.caption.weight(.semibold))
                }
                .foregroundStyle(Color.luTint)
                .padding(.horizontal, 11)
                .padding(.vertical, 5)
                .background(Capsule().fill(Color.luTint.opacity(0.13)))
            }
            Text(title)
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(.primary)
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A grouped-list section caption (uppercase, secondary), matching the inset-grouped list idiom.
struct MetadataGroupHeader: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.footnote)
            .foregroundStyle(Color.luLabel2)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview("MetadataFlowHeader") {
    VStack(spacing: 24) {
        MetadataFlowHeader(
            badge: "Match metadata",
            title: "Find on Audible",
            subtitle: "Search Audible for this book, then choose the edition that matches your audiobook."
        )
        MetadataGroupHeader(text: "Audible region")
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    .background(Color.luSurface)
}
