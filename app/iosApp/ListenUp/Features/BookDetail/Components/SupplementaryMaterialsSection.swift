import SwiftUI

/// The "Supplementary materials" block on Book Detail: a heading then one tappable card
/// per document (format icon · basename · "<FORMAT> · <size>"). A card shows a spinner
/// while its bytes download (`openingDocIds`). PDFs open the reader; other formats route
/// to a "coming soon" path in the view model.
struct SupplementaryMaterialsSection: View {
    let documents: [DocumentRow]
    let openingDocIds: Set<String>
    let onOpen: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "book.detail_supplementary_materials"))
                .font(.headline)
                .padding(.bottom, 2)
            ForEach(documents, id: \.id) { doc in card(doc) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func card(_ doc: DocumentRow) -> some View {
        Button { onOpen(doc.id) } label: {
            HStack(spacing: 14) {
                Image(systemName: documentFormatSymbol(doc.format))
                    .font(.title3)
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(width: 44, height: 44)
                    .background(
                        Color.listenUpOrange.opacity(0.14),
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(documentBasename(doc.filename)).font(.body).foregroundStyle(.primary).lineLimit(1)
                    Text("\(doc.format.uppercased()) · \(formatDocumentSize(doc.size))")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if openingDocIds.contains(doc.id) {
                    ProgressView()
                } else {
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
            }
            .padding(14)
            .background(
                Color(.secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(openingDocIds.contains(doc.id))
        .accessibilityLabel("\(documentBasename(doc.filename)), \(doc.format.uppercased()), \(formatDocumentSize(doc.size))")
    }
}
