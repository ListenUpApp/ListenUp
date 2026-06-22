import PDFKit
import SwiftUI

/// In-document search: a field + a results list bound to a `PdfSearchController`.
/// Tapping a result reports its selection (to highlight + jump) and the caller dismisses.
struct DocumentSearchView: View {
    @Bindable var controller: PdfSearchController
    var onSelect: (PDFSelection) -> Void
    var onClose: () -> Void

    @State private var text = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                if !text.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(
                        controller.isSearching
                            ? String(localized: "common.loading")
                            : searchResultCountText(controller.hits.count)
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
                List(controller.hits) { hit in
                    Button {
                        onSelect(hit.selection)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(
                                String(
                                    format: String(localized: "book.reader_scrubber_page_label"),
                                    hit.pageDisplay
                                )
                            )
                            .font(.caption)
                            .foregroundStyle(Color.listenUpOrange)
                            Text(hit.snippet)
                                .font(.callout)
                                .foregroundStyle(.primary)
                                .lineLimit(3)
                        }
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle(String(localized: "book.detail_document_search"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(String(localized: "common.cancel")) { onClose() }
                }
            }
            .searchable(
                text: $text,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: String(localized: "book.detail_document_search_placeholder")
            )
            .onChange(of: text) { _, newText in controller.update(query: newText) }
            .overlay {
                if !controller.isSearching,
                   controller.hits.isEmpty,
                   !text.trimmingCharacters(in: .whitespaces).isEmpty {
                    ContentUnavailableView(
                        String(localized: "book.detail_document_search_no_results"),
                        systemImage: "magnifyingglass"
                    )
                }
            }
        }
    }
}
