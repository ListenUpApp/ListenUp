import SwiftUI
import Shared

// Two responsive presentations of the grouped hits:
//  - `SearchResultsList`  — a single grouped `List` (compact / iPhone).
//  - `SearchResultsPad`   — a width-driven two-column layout (regular / iPad, Split View).
//
// Each group renders at most its `SearchResultCaps` prefix; a group that overflows its cap
// shows a "See all" affordance in its section header that pushes the full single-type page.

/// Compact layout: one grouped `List`, a section per non-empty kind.
struct SearchResultsList: View {
    let groups: SearchHitGroups
    let onTap: (SearchRow) -> Void
    let onSeeAll: (SearchSeeAllType) -> Void

    var body: some View {
        List {
            section(SearchSectionTitle.books, groups.cappedBooks) { hit in
                SearchBookRow(row: hit) { onTap(hit) }
            }
            section(SearchSectionTitle.people, groups.cappedPeople) { hit in
                SearchPersonRow(row: hit) { onTap(hit) }
            }
            section(SearchSectionTitle.series, groups.cappedSeries) { hit in
                SearchSeriesRow(row: hit) { onTap(hit) }
            }
            if !groups.tags.isEmpty {
                Section(SearchSectionTitle.tags(groups.tags.count)) {
                    SearchTagsFlow(tags: groups.tags, onTap: onTap)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    @ViewBuilder
    private func section(
        _ title: (Int) -> String,
        _ group: CappedGroup,
        @ViewBuilder row: @escaping (SearchRow) -> some View
    ) -> some View {
        if !group.hits.isEmpty {
            Section {
                ForEach(group.hits, id: \.id) { hit in row(hit) }
            } header: {
                SearchListSectionHeader(
                    title: title(group.totalCount),
                    seeAllType: group.seeAllType,
                    onSeeAll: onSeeAll
                )
            }
        }
    }
}

/// Regular-width layout: Books flow into a width-driven cover grid on the left;
/// People + Series stack as grouped lists on the right; Tags span full width below.
/// Columns emerge from the available width (`GridItem(.adaptive)`), so it stays right
/// across full-screen iPad, Split View, and Stage Manager — not just at one breakpoint.
struct SearchResultsPad: View {
    let groups: SearchHitGroups
    let onTap: (SearchRow) -> Void
    let onSeeAll: (SearchSeeAllType) -> Void

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
        let group = groups.cappedBooks
        return VStack(alignment: .leading, spacing: 12) {
            SearchSectionHeader(
                title: SearchSectionTitle.books(group.totalCount),
                seeAllType: group.seeAllType,
                onSeeAll: onSeeAll
            )
            LazyVGrid(columns: coverColumns, alignment: .leading, spacing: 20) {
                ForEach(group.hits, id: \.id) { hit in
                    Button { onTap(hit) } label: { SearchBookCard(row: hit) }
                        .buttonStyle(.plain)
                }
            }
        }
    }

    private var peopleAndSeriesColumn: some View {
        VStack(alignment: .leading, spacing: 24) {
            railSection(groups.cappedPeople, title: SearchSectionTitle.people) { hit in
                SearchPersonRow(row: hit) { onTap(hit) }
            }
            railSection(groups.cappedSeries, title: SearchSectionTitle.series) { hit in
                SearchSeriesRow(row: hit) { onTap(hit) }
            }
        }
    }

    @ViewBuilder
    private func railSection(
        _ group: CappedGroup,
        title: (Int) -> String,
        @ViewBuilder row: @escaping (SearchRow) -> some View
    ) -> some View {
        if !group.hits.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SearchSectionHeader(
                    title: title(group.totalCount),
                    seeAllType: group.seeAllType,
                    onSeeAll: onSeeAll
                )
                ForEach(group.hits, id: \.id) { hit in row(hit) }
            }
        }
    }
}

/// A vertical book card for the iPad cover grid: cover + title + author.
private struct SearchBookCard: View {
    let row: SearchRow

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverImage(bookId: row.id, coverPath: row.coverPath, coverHash: row.coverHash)
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(row.name)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(2)
            if let author = row.author, !author.isEmpty {
                Text(author)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

/// `List` section header with the localized "Title (count)" and a trailing "See all" when
/// the group overflowed its cap. Pushes the full single-type page.
private struct SearchListSectionHeader: View {
    let title: String
    let seeAllType: SearchSeeAllType?
    let onSeeAll: (SearchSeeAllType) -> Void

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            if let seeAllType {
                Button(String(localized: "search.see_all")) { onSeeAll(seeAllType) }
                    .font(.subheadline)
                    .textCase(nil)
            }
        }
    }
}

/// Inline section header for the iPad columns (the `List` uses native `Section` headers).
private struct SearchSectionHeader: View {
    let title: String
    var seeAllType: SearchSeeAllType?
    var onSeeAll: ((SearchSeeAllType) -> Void)?

    var body: some View {
        HStack {
            Text(title)
                .font(.headline)
                .foregroundStyle(.secondary)
            Spacer()
            if let seeAllType, let onSeeAll {
                Button(String(localized: "search.see_all")) { onSeeAll(seeAllType) }
                    .font(.subheadline)
            }
        }
    }
}

/// Localized "Title (count)" section titles, shared by both layouts.
enum SearchSectionTitle {
    static func books(_ count: Int) -> String { titled(String(localized: "library.books"), count) }
    static func people(_ count: Int) -> String { titled(String(localized: "search.people"), count) }
    static func series(_ count: Int) -> String { titled(String(localized: "common.series"), count) }
    static func tags(_ count: Int) -> String { titled(String(localized: "book.detail_tags"), count) }

    private static func titled(_ title: String, _ count: Int) -> String {
        "\(title) \(String(format: String(localized: "search.section_count"), count))"
    }
}
