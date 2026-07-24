import SwiftUI

/// The full authors roster for a series, opened from the collapsed "{first} & N others" hero line.
/// Lists every author across the series' books, each tappable to its contributor page. Authors-only
/// — no role grouping (narrators are intentionally not shown on the Series page). Modeled on
/// `CastCreditsSheet` but operates on native `SeriesAuthor` values, never bridged KMP objects.
struct SeriesAuthorsSheet: View {
    let authors: [SeriesAuthor]
    let onClose: () -> Void

    private static let avatarHues: [Color] = [
        Color(hex: "#7A5AF8"), Color(hex: "#1F8A5B"), Color(hex: "#E0613A"),
        Color(hex: "#2A6FDB"), Color(hex: "#C2389B"), Color(hex: "#0E8C8C")
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 220), spacing: 16)],
                          alignment: .leading, spacing: 4) {
                    ForEach(Array(authors.enumerated()), id: \.element.id) { index, author in
                        NavigationLink(value: ContributorDestination(id: author.id)) {
                            row(author, hueIndex: index)
                        }
                        .buttonStyle(.plain)
                        .simultaneousGesture(TapGesture().onEnded { onClose() })
                    }
                }
                .padding(20)
            }
            .navigationTitle(String(localized: "book.detail_authors"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done")) { onClose() }
                }
            }
            .safeAreaInset(edge: .top) {
                Text(String(format: String(localized: "book.detail_cast_count_authors"), authors.count))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 4)
            }
        }
    }

    private func row(_ author: SeriesAuthor, hueIndex: Int) -> some View {
        HStack(spacing: 12) {
            avatar(author, hueIndex: hueIndex)
            Text(author.name)
                .font(.subheadline)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 7)
        .accessibilityElement(children: .combine)
    }

    private func avatar(_ author: SeriesAuthor, hueIndex: Int) -> some View {
        let hue = Self.avatarHues[abs(hueIndex) % Self.avatarHues.count]
        return Text(initials(author.name))
            .font(.subheadline.weight(.bold))
            .foregroundStyle(hue)
            .frame(width: 40, height: 40)
            .background(hue.opacity(0.17), in: Circle())
            .accessibilityHidden(true)
    }

    private func initials(_ name: String) -> String {
        let parts = name.split(separator: " ")
        let first = parts.first?.first.map(String.init) ?? ""
        let last = parts.count > 1 ? (parts.last?.first.map(String.init) ?? "") : ""
        return (first + last).uppercased()
    }
}
