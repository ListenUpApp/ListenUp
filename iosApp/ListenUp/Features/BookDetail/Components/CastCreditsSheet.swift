import SwiftUI
import Shared

/// Role-grouped Cast & Credits sheet (Author / Narrators / Production). Presented as a
/// native sheet from Book Detail; responsive two-column layout where width allows.
/// Maps the book's KMP contributors into native `CastMember`s, then groups via `castGroups`.
struct CastCreditsSheet: View {
    let book: BookDetail
    let onClose: () -> Void

    private static let avatarHues: [Color] = [
        Color(hex: "#7A5AF8"), Color(hex: "#1F8A5B"), Color(hex: "#E0613A"),
        Color(hex: "#2A6FDB"), Color(hex: "#C2389B"), Color(hex: "#0E8C8C"),
    ]

    private var members: (authors: [CastMember], narrators: [CastMember], all: [CastMember]) {
        func map(_ c: BookContributor) -> CastMember {
            CastMember(id: c.id, name: c.name, roles: Array(c.roles))
        }
        return (book.authors.map(map), book.narrators.map(map), book.allContributors.map(map))
    }

    private var groups: [CastGroup] {
        let m = members
        return castGroups(authors: m.authors, narrators: m.narrators, all: m.all)
    }

    private var totalCount: Int { members.all.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 220), spacing: 16)],
                          alignment: .leading, spacing: 4) {
                    ForEach(Array(groups.enumerated()), id: \.element.id) { groupIndex, group in
                        Section {
                            ForEach(Array(group.members.enumerated()), id: \.element.id) { i, member in
                                // offset hue per group so adjacent groups don't repeat colors
                                NavigationLink(value: ContributorDestination(id: member.id)) {
                                    row(member, hueIndex: groupIndex * 7 + i)
                                }
                                .buttonStyle(.plain)
                            }
                        } header: {
                            Text(header(for: group))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .textCase(.uppercase)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.top, 14)
                        }
                    }
                }
                .padding(20)
            }
            .navigationTitle(String(localized: "book.detail_credits"))
            .navigationBarTitleDisplayMode(.inline)
            // The sheet is its own navigation hierarchy, so the tab-level destination doesn't
            // reach inside it — register the contributor route here so a tapped row pushes.
            .navigationDestination(for: ContributorDestination.self) { destination in
                ContributorDetailView(contributorId: destination.id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done")) { onClose() }
                }
            }
            .safeAreaInset(edge: .top) {
                Text(String(format: String(localized: "book.detail_credits_subtitle"), totalCount, book.title))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 4)
            }
        }
    }

    private func header(for group: CastGroup) -> String {
        switch group.kind {
        case .authors:
            return group.members.count > 1
                ? String(localized: "book.detail_cast_authors")
                : String(localized: "book.detail_cast_author")
        case .narrators:
            let key: String = group.members.count == 1
                ? "book.detail_cast_narrator"
                : "book.detail_cast_narrators"
            return String(format: String(localized: String.LocalizationValue(key)), group.members.count)
        case .production:
            return String(localized: "book.detail_cast_production")
        }
    }

    private func row(_ member: CastMember, hueIndex: Int) -> some View {
        HStack(spacing: 12) {
            avatar(member, hueIndex: hueIndex)
            Text(member.name)
                .font(.subheadline)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 7)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func avatar(_ member: CastMember, hueIndex: Int) -> some View {
        let hue = Self.avatarHues[abs(hueIndex) % Self.avatarHues.count]
        return Text(initials(member.name))
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
