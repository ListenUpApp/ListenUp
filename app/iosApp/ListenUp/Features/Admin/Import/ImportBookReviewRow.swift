import SwiftUI

/// One ambiguous/unmatched ABS book item in the import Review step: an identity line (title +
/// identifiers + a status pill), then a resolution affordance — either the inline book-search
/// panel (when open for this item) or Search-&-assign / Skip actions.
///
/// Bound to an ``ImportBookRowModel``. The search panel is driven by the shared VM: the text field
/// reflects the VM's query and reports edits back; results and the spinner come from the flattened
/// snapshot. This row only reports intent via its callbacks.
struct ImportBookReviewRow: View {
    let book: ImportBookRowModel
    /// The open search panel when it belongs to THIS item, else nil.
    let search: ImportBookSearchModel?
    let onOpenSearch: () -> Void
    let onCloseSearch: () -> Void
    let onQueryChange: (String) -> Void
    let onSelectBook: (String) -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            identityRow
            Divider().padding(.leading, 14)
            resolutionRow
        }
        .fieldCard()
    }

    // MARK: - Identity

    private var identityRow: some View {
        HStack(alignment: .top, spacing: 13) {
            VStack(alignment: .leading, spacing: 2) {
                Text(book.title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                if !book.identifiers.isEmpty {
                    Text(book.identifiers)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer(minLength: 8)
            statusPill
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    @ViewBuilder
    private var statusPill: some View {
        switch book.resolution {
        case .needsReview:
            Text(String(localized: "import.user_needs_review"))
                .font(.caption.weight(.bold))
                .foregroundStyle(.orange)
                .padding(.horizontal, 11)
                .padding(.vertical, 5)
                .background(Color.orange.opacity(0.16), in: Capsule())
        case .assigned, .skipped:
            Label(
                book.resolution == .skipped
                    ? String(localized: "import.user_skipped")
                    : String(localized: "import.book_assigned"),
                systemImage: "checkmark"
            )
            .labelStyle(.titleAndIcon)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(book.resolution == .skipped ? Color.luLabel2 : .green)
        }
    }

    // MARK: - Resolution

    @ViewBuilder
    private var resolutionRow: some View {
        if let search {
            searchPanel(search)
        } else {
            actionRow
        }
    }

    private var actionRow: some View {
        HStack(spacing: 0) {
            Button(action: onOpenSearch) {
                Label(String(localized: "import.search_and_assign"), systemImage: "sparkles")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.pressScaleChip)
            Divider().frame(height: 24)
            Button(action: onSkip) {
                Label(String(localized: "import.book_skip"), systemImage: "xmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.pressScaleChip)
        }
        .padding(.vertical, 13)
    }

    // MARK: - Search panel

    private func searchPanel(_ search: ImportBookSearchModel) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                TextField(
                    String(localized: "import.book_search_hint"),
                    text: Binding(get: { search.query }, set: onQueryChange)
                )
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                if search.isSearching {
                    ProgressView().controlSize(.small)
                }
                Button(action: onCloseSearch) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Color.luLabel2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "import.book_search_cancel"))
            }

            if !search.query.isEmpty, !search.isSearching, search.results.isEmpty {
                Text(String(localized: "import.book_search_no_results"))
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
            }

            ForEach(search.results) { hit in
                Button {
                    onSelectBook(hit.bookId)
                } label: {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(hit.title)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if !hit.author.isEmpty {
                            Text(hit.author)
                                .font(.footnote)
                                .foregroundStyle(Color.luLabel2)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if hit.id != search.results.last?.id {
                    Divider()
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

// MARK: - Preview

#Preview("ImportBookReviewRow") {
    ScrollView {
        VStack(spacing: 18) {
            ImportBookReviewRow(
                book: ImportBookRowModel(
                    absItemId: "1", title: "The Way of Kings", asin: "B0041JKFJW", isbn: nil,
                    isUnmatched: false, resolution: .needsReview
                ),
                search: nil,
                onOpenSearch: {}, onCloseSearch: {}, onQueryChange: { _ in },
                onSelectBook: { _ in }, onSkip: {}
            )
            ImportBookReviewRow(
                book: ImportBookRowModel(
                    absItemId: "2", title: "Mistborn", asin: nil, isbn: "9780765311788",
                    isUnmatched: true, resolution: .assigned(bookId: "b2")
                ),
                search: nil,
                onOpenSearch: {}, onCloseSearch: {}, onQueryChange: { _ in },
                onSelectBook: { _ in }, onSkip: {}
            )
            ImportBookReviewRow(
                book: ImportBookRowModel(
                    absItemId: "3", title: "Words of Radiance", asin: nil, isbn: nil,
                    isUnmatched: false, resolution: .needsReview
                ),
                search: ImportBookSearchModel(
                    absItemId: "3", query: "radiance", isSearching: false,
                    results: [ImportBookSearchHit(bookId: "b3", title: "Words of Radiance", author: "Brandon Sanderson")]
                ),
                onOpenSearch: {}, onCloseSearch: {}, onQueryChange: { _ in },
                onSelectBook: { _ in }, onSkip: {}
            )
        }
        .padding()
    }
    .background(Color.luSurface)
}
