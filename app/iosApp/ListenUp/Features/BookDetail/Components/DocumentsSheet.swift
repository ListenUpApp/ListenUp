import SwiftUI

/// Maps & Illustrations (spec §3.2.1 D36), when the book has more than one document: a sheet of document rows (format
/// glyph · name · "<FORMAT> · <size>"). A row shows a spinner while its bytes download (`openingDocIds`). PDFs open the
/// reader; other formats route to a "coming soon" path in the view model. One document opens straight from Details.
struct DocumentsSheet: View {
    let documents: [DocumentRow]
    let openingDocIds: Set<String>
    let onOpen: (String) -> Void
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            List(documents, id: \.id) { doc in row(doc) }
                .navigationTitle(String(localized: "book.detail_documents_sheet_title_ios"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(String(localized: "common.done"), action: onDone)
                    }
                }
        }
    }

    private func row(_ doc: DocumentRow) -> some View {
        Button { onOpen(doc.id) } label: {
            HStack(spacing: 14) {
                Image(systemName: documentFormatSymbol(doc.format))
                    .font(.title3)
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(documentBasename(doc.filename)).font(.body).foregroundStyle(.primary).lineLimit(1)
                    Text("\(doc.format.uppercased()) · \(formatDocumentSize(doc.size))")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if openingDocIds.contains(doc.id) { ProgressView() }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(openingDocIds.contains(doc.id))
        .accessibilityLabel("\(documentBasename(doc.filename)), \(doc.format.uppercased()), \(formatDocumentSize(doc.size))")
    }
}
