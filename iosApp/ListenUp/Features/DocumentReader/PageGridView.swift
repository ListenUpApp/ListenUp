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
                        cell(index)
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

    @ViewBuilder
    private func cell(_ index: Int) -> some View {
        Button { onSelect(index) } label: {
            VStack(spacing: 5) {
                thumbnail(index)
                    .overlay(
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .stroke(index == currentPageIndex ? Color.listenUpOrange : Color.clear, lineWidth: 2.5)
                    )
                Text("\(index + 1)")
                    .font(.caption2).monospacedDigit()
                    .foregroundStyle(index == currentPageIndex ? Color.listenUpOrange : .secondary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(format: String(localized: "book.reader_scrubber_page_label"), index + 1))
    }

    @ViewBuilder
    private func thumbnail(_ index: Int) -> some View {
        if let page = document.page(at: index) {
            Image(uiImage: page.thumbnail(of: CGSize(width: 160, height: 210), for: .cropBox))
                .resizable().aspectRatio(contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: 4).fill(Color(.secondarySystemFill)).aspectRatio(0.77, contentMode: .fit)
        }
    }
}
