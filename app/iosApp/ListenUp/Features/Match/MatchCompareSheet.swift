import SwiftUI

/// Compare with your copy: the facts that tell editions apart, side by side, from Find's own data — no
/// extra call. A quick look in a resizable sheet (HIG, Sheets) that leads to Review or back to the results.
struct MatchCompareSheet: View {
    let yourCopy: MatchYourCopy?
    let candidate: MatchCandidateRow
    let onReview: () -> Void
    let onBack: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(alignment: .top, spacing: Spacing.m) {
                        column(
                            title: String(localized: "match.your_copy"),
                            caption: String(localized: "match.in_your_library")
                        ) {
                            BookCoverImage(coverPath: yourCopy?.coverPath, coverHash: yourCopy?.coverHash)
                        }
                        column(title: String(localized: "match.this_match"), caption: candidate.foundInLine) {
                            MetadataRemoteCover(url: candidate.coverURL)
                        } badges: {
                            MatchBadges(isBest: candidate.isBest, isCurrentLink: candidate.isCurrentLink)
                        }
                    }
                }

                Section {
                    ForEach(Self.rows(yourCopy: yourCopy?.compare, candidate: candidate.compare)) { row in
                        MatchCompareRowView(row: row)
                    }
                }

                Section {
                    Button {
                        onReview()
                    } label: {
                        ActionLabel(title: String(localized: "match.review_this_match_title"))
                    }
                    .prominentAction()
                    .accessibilityLabel(String(
                        format: String(localized: "match.review_this_match_a11y"), candidate.title, candidate.foundInList
                    ))
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())

                    Button(String(localized: "match.back_to_results_title"), action: onBack)
                        .frame(maxWidth: .infinity, minHeight: TapTarget.minimum)
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(String(localized: "match.compare_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.close"), systemImage: "xmark", action: onBack)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    /// One edition at the top of Compare: its cover, what it is, and where it lives.
    private func column<Cover: View, Badges: View>(
        title: String,
        caption: String,
        @ViewBuilder cover: () -> Cover,
        @ViewBuilder badges: () -> Badges = { EmptyView() }
    ) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            cover()
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                .accessibilityHidden(true)
            Text(title).font(.subheadline.weight(.semibold))
            badges()
            if !caption.isEmpty {
                Text(caption).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// One fact for both editions.
    struct Row: Identifiable, Equatable {
        let name: String
        let yours: String
        let theirs: String
        var id: String { name }
    }

    /// Length, Narrator, Chapters, Year, Format, Store, Found in — a missing value reads "Not listed".
    static func rows(yourCopy: MatchCompareValues?, candidate: MatchCompareValues) -> [Row] {
        let missing = String(localized: "match.not_listed")
        let pairs: [(String, KeyPath<MatchCompareValues, String?>)] = [
            (String(localized: "match.row_length"), \.length),
            (String(localized: "match.row_narrator"), \.narrator),
            (String(localized: "match.row_chapters"), \.chapters),
            (String(localized: "match.row_year"), \.year),
            (String(localized: "match.row_format"), \.format),
            (String(localized: "match.row_store"), \.store),
            (String(localized: "match.row_found_in"), \.foundIn)
        ]
        return pairs.map { name, path in
            Row(name: name, yours: yourCopy?[keyPath: path] ?? missing, theirs: candidate[keyPath: path] ?? missing)
        }
    }
}

private struct MatchCompareRowView: View {
    let row: MatchCompareSheet.Row
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            Text(row.name).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xxs))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Spacing.s))
            layout {
                Text(row.yours).frame(maxWidth: .infinity, alignment: .leading)
                Text(row.theirs).frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.body)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.name)
        .accessibilityValue(String(
            format: "%@: %@. %@: %@.",
            String(localized: "match.your_copy"), row.yours, String(localized: "match.this_match"), row.theirs
        ))
    }
}
