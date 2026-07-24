import PDFKit
import SwiftUI

/// Persistent iPad page-thumbnail rail beside the reader. Single column; current page outlined.
struct PageRailView: View {
    let document: PDFDocument
    let currentPageIndex: Int
    var onSelect: (Int) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(0..<document.pageCount, id: \.self) { index in
                    PageThumbnailCell(
                        document: document,
                        index: index,
                        isCurrent: index == currentPageIndex,
                        onTap: { onSelect(index) }
                    )
                }
            }
            .padding(.vertical, 12)
        }
        .frame(width: 108)
        .background(Color(.secondarySystemBackground))
    }
}
