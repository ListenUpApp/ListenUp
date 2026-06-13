import SwiftUI
@preconcurrency import Shared

// Two responsive presentations of the grouped hits:
//  - `SearchResultsList`  — a single grouped `List` (compact / iPhone).
//  - `SearchResultsPad`   — a width-driven two-column layout (regular / iPad, Split View).

/// Compact layout: one grouped `List`, a section per non-empty kind.
struct SearchResultsList: View {
    let groups: SearchHitGroups
    let onTap: (SearchHit) -> Void

    var body: some View {
        List {
            if !groups.books.isEmpty {
                Section(SearchSectionTitle.books(groups.books.count)) {
                    ForEach(groups.books, id: \.id) { hit in SearchBookRow(hit: hit) { onTap(hit) } }
                }
            }
            if !groups.people.isEmpty {
                Section(SearchSectionTitle.people(groups.people.count)) {
                    ForEach(groups.people, id: \.id) { hit in SearchPersonRow(hit: hit) { onTap(hit) } }
                }
            }
            if !groups.series.isEmpty {
                Section(SearchSectionTitle.series(groups.series.count)) {
                    ForEach(groups.series, id: \.id) { hit in SearchSeriesRow(hit: hit) { onTap(hit) } }
                }
            }
            if !groups.tags.isEmpty {
                Section(SearchSectionTitle.tags(groups.tags.count)) {
                    SearchTagsFlow(tags: groups.tags, onTap: onTap)
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// Regular-width layout: Books flow into a width-driven cover grid on the left;
/// People + Series stack as grouped lists on the right; Tags span full width below.
/// Columns emerge from the available width (`GridItem(.adaptive)`), so it stays right
/// across full-screen iPad, Split View, and Stage Manager — not just at one breakpoint.
struct SearchResultsPad: View {
    let groups: SearchHitGroups
    let onTap: (SearchHit) -> Void

    private let coverColumns = [GridItem(.adaptive(minimum: 140), spacing: 20)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                HStack(alignment: .top, spacing: 32) {
                    if !groups.books.isEmpty {
                        booksColumn
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                    }
                    if !groups.people.isEmpty || !groups.series.isEmpty {
                        peopleAndSeriesColumn
                            .frame(maxWidth: 360, alignment: .topLeading)
                    }
                }
                if !groups.tags.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        SearchSectionHeader(title: SearchSectionTitle.tags(groups.tags.count))
                        SearchTagsFlow(tags: groups.tags, onTap: onTap)
                    }
                }
            }
            .padding(24)
        }
    }

    private var booksColumn: some View {
        VStack(alignment: .leading, spacing: 12) {
            SearchSectionHeader(title: SearchSectionTitle.books(groups.books.count))
            LazyVGrid(columns: coverColumns, alignment: .leading, spacing: 20) {
                ForEach(groups.books, id: \.id) { hit in
                    Button { onTap(hit) } label: { SearchBookCard(hit: hit) }
                        .buttonStyle(.plain)
                }
            }
        }
    }

    private var peopleAndSeriesColumn: some View {
        VStack(alignment: .leading, spacing: 24) {
            if !groups.people.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    SearchSectionHeader(title: SearchSectionTitle.people(groups.people.count))
                    ForEach(groups.people, id: \.id) { hit in SearchPersonRow(hit: hit) { onTap(hit) } }
                }
            }
            if !groups.series.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    SearchSectionHeader(title: SearchSectionTitle.series(groups.series.count))
                    ForEach(groups.series, id: \.id) { hit in SearchSeriesRow(hit: hit) { onTap(hit) } }
                }
            }
        }
    }
}

/// A vertical book card for the iPad cover grid: cover + title + author.
private struct SearchBookCard: View {
    let hit: SearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverImage(bookId: hit.id, coverPath: hit.coverPath, blurHash: nil)
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(hit.name)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(2)
            if let author = hit.author, !author.isEmpty {
                Text(author)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

/// Inline section header for the iPad columns (the `List` uses native `Section` headers).
private struct SearchSectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.headline)
            .foregroundStyle(.secondary)
    }
}

/// Localized "Title (count)" section titles, shared by both layouts.
private enum SearchSectionTitle {
    static func books(_ count: Int) -> String { titled(String(localized: "library.books"), count) }
    static func people(_ count: Int) -> String { titled(String(localized: "search.people"), count) }
    static func series(_ count: Int) -> String { titled(String(localized: "common.series"), count) }
    static func tags(_ count: Int) -> String { titled(String(localized: "book.detail_tags"), count) }

    private static func titled(_ title: String, _ count: Int) -> String {
        "\(title) \(String(format: String(localized: "search.section_count"), count))"
    }
}
