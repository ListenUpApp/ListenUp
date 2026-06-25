import SwiftUI

/// The one reusable add-picker for a Book-Edit relation (contributor / series / genre / tag / mood).
///
/// It is a single inline search field that shows live results beneath it as the user types, taps to
/// attach, and — for relations that support free-text creation — offers a "Create …" affordance when
/// the typed query has no exact match. The six relation sections compose this one view rather than
/// copy-pasting a picker each; only the per-relation wiring (query setter, result selector, optional
/// creator) differs, and that is passed in as closures.
///
/// All inputs are native Swift value types (`RelationSearchResult`) mapped at the observer boundary —
/// no Swift Export-bridged Kotlin object ever reaches the results `ForEach`.
struct RelationSearchField: View {
    /// The placeholder / accessibility prompt (e.g. "Add author…").
    let placeholder: String
    /// The current query text.
    let query: String
    /// Live search results to show beneath the field.
    let results: [RelationSearchResult]
    /// Whether a search/create is in flight (drives the trailing spinner).
    let isLoading: Bool
    /// Whether free-text creation is allowed. When `true` and the query has no exact match, a
    /// "Create …" row appears so the user can add a brand-new contributor / series / tag / mood.
    let allowsCreate: Bool

    let onQueryChange: (String) -> Void
    let onSelect: (RelationSearchResult) -> Void
    /// Called when the user creates a new entry from free text. `nil` for select-only relations
    /// (genres), in which case `allowsCreate` must be `false`.
    let onCreate: ((String) -> Void)?

    @FocusState private var isFocused: Bool

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }

    /// The create row shows only when creation is permitted, the query is long enough, nothing is
    /// loading, and no result already matches the typed name exactly (case-insensitively).
    private var showsCreateRow: Bool {
        guard allowsCreate, onCreate != nil, !isLoading, trimmedQuery.count >= 2 else { return false }
        return !results.contains { $0.name.caseInsensitiveCompare(trimmedQuery) == .orderedSame }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            AppTextField(
                placeholder: placeholder,
                text: Binding(get: { query }, set: { onQueryChange($0) }),
                kind: .search,
                submitLabel: .done,
                onSubmit: submit
            )
            .fieldCard()
            .focused($isFocused)
            .overlay(alignment: .trailing) {
                if isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .padding(.trailing, 14)
                }
            }

            if !results.isEmpty || showsCreateRow {
                resultsList
            } else if !trimmedQuery.isEmpty, !isLoading {
                Text(String(localized: "book.edit_no_matches"))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel3)
                    .padding(.horizontal, 4)
            }
        }
    }

    private var resultsList: some View {
        VStack(spacing: 0) {
            ForEach(results) { result in
                resultRow(result)
                if result.id != results.last?.id || showsCreateRow {
                    Divider().padding(.leading, 14)
                }
            }
            if showsCreateRow {
                createRow
            }
        }
        .fieldCard()
    }

    private func resultRow(_ result: RelationSearchResult) -> some View {
        Button { onSelect(result) } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(result.name)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if let subtitle = result.subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(Color.luLabel3)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "plus.circle.fill")
                    .font(.body)
                    .foregroundStyle(Color.luTint)
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(format: String(localized: "common.add_name"), result.name))
    }

    private var createRow: some View {
        Button { onCreate?(trimmedQuery) } label: {
            HStack(spacing: 10) {
                Image(systemName: "plus")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                Text(String(format: String(localized: "book.edit_add_trimmedquery"), trimmedQuery))
                    .font(.subheadline)
                    .foregroundStyle(Color.luTint)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(format: String(localized: "common.add_name"), trimmedQuery))
    }

    /// Submit picks the top result if there is one, otherwise creates from free text when allowed.
    private func submit() {
        if let top = results.first {
            onSelect(top)
        } else if allowsCreate, let onCreate, trimmedQuery.count >= 2 {
            onCreate(trimmedQuery)
        }
    }
}
