import PDFKit
import SwiftUI

/// Full-screen "All Pages" thumbnail grid. Tapping a page reports its 0-based index and
/// dismisses, returning to the reader at that page. The current page is outlined.
struct PageGridView: View {
    let document: PDFDocument
    let currentPageIndex: Int
    var onSelect: (Int) -> Void
    var onClose: () -> Void

    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 14)]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 16) {
                    ForEach(0..<document.pageCount, id: \.self) { index in
                        PageThumbnailCell(
                            document: document,
                            index: index,
                            isCurrent: index == currentPageIndex,
                            onTap: { onSelect(index) }
                        )
                    }
                }
                .padding(16)
            }
            .navigationTitle(String(localized: "book.detail_document_pages_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onClose) {
                        Label(String(localized: "book.detail_document_pages_back"), systemImage: "chevron.left")
                    }
                }
            }
        }
    }
}
