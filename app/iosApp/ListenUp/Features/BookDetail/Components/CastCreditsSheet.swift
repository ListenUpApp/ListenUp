import SwiftUI
import Shared

/// Role-grouped Cast & Credits sheet (Author / Narrators / Production). Presented as a
/// native sheet from Book Detail; responsive two-column layout where width allows.
/// Maps the book's KMP contributors into native `CastMember`s, then groups via `castGroups`.
struct CastCreditsSheet: View {
    let book: BookDetail
    let onClose: () -> Void

    /// Pushes a contributor's full page onto the main tab stack (provided by `MainTabView`). We
    /// navigate there rather than inside this sheet so a tapped cast member opens their full page.
    @Environment(\.navigateToContributor) private var navigateToContributor

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
                    ForEach(groups, id: \.id) { group in
                        Section {
                            ForEach(group.members, id: \.id) { member in
                                // Push the contributor onto the main tab stack (full page), then
                                // dismiss this sheet — not an in-sheet push.
                                Button {
                                    navigateToContributor(member.id)
                                    onClose()
                                } label: {
                                    row(member)
                                }
                                .buttonStyle(.plain)
                            }
                        } header: {
                            Text(header(for: group))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .textCase(.uppercase)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.top, Spacing.m)
                        }
                    }
                }
                .padding(Spacing.l)
            }
            .navigationTitle(String(localized: "book.detail_credits"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done")) { onClose() }
                }
            }
            .safeAreaInset(edge: .top) {
                Text(String(format: String(localized: "book.detail_credits_subtitle"), totalCount, book.title))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Spacing.l)
                    .padding(.bottom, Spacing.xxs)
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

    private func row(_ member: CastMember) -> some View {
        HStack(spacing: 12) {
            avatar(member)
            Text(member.name)
                .font(.subheadline)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.vertical, Spacing.xs)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    /// The shared `AvatarPalette` fill, keyed by the contributor id so a person wears the same
    /// colour here as on their own page.
    private func avatar(_ member: CastMember) -> some View {
        Text(initials(member.name))
            .font(.subheadline.weight(.bold))
            .foregroundStyle(AvatarPalette.initialsInk)
            .frame(width: 40, height: 40)
            .background(AvatarPalette.fill(forKey: member.id), in: Circle())
            .accessibilityHidden(true)
    }

    private func initials(_ name: String) -> String {
        let parts = name.split(separator: " ")
        let first = parts.first?.first.map(String.init) ?? ""
        let last = parts.count > 1 ? (parts.last?.first.map(String.init) ?? "") : ""
        return (first + last).uppercased()
    }
}
