import SwiftUI
@preconcurrency import Shared

/// The "Supplementary materials" block on Book Detail: a heading then one tappable row
/// per document (format icon · basename · "<FORMAT> · <size>"). A row shows a spinner
/// while its bytes download (`openingDocIds`). PDFs open the reader; other formats route
/// to a "coming soon" path in the view model.
struct SupplementaryMaterialsSection: View {
    let documents: [BookDocument]
    let openingDocIds: Set<String>
    let tint: Color
    let onOpen: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(String(localized: "book.detail_supplementary_materials"))
                .font(.headline)
                .padding(.bottom, 8)

            ForEach(Array(documents.enumerated()), id: \.element.id) { index, doc in
                if index > 0 { Divider() }
                row(doc)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ doc: BookDocument) -> some View {
        Button { onOpen(doc.id) } label: {
            HStack(spacing: 12) {
                Image(systemName: documentFormatSymbol(doc.format))
                    .font(.title3)
                    .foregroundStyle(tint)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(documentBasename(doc.filename))
                        .font(.body)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text("\(doc.format.uppercased()) · \(formatDocumentSize(doc.size))")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if openingDocIds.contains(doc.id) {
                    ProgressView()
                } else {
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(openingDocIds.contains(doc.id))
        .accessibilityLabel("\(documentBasename(doc.filename)), \(doc.format.uppercased()), \(formatDocumentSize(doc.size))")
    }
}
