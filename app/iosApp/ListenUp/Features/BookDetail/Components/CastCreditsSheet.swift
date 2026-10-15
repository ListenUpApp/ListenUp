import SwiftUI

/// Role-grouped Cast & Credits sheet (Author / Narrators / Production). Presented as a
/// native sheet from Book Detail; responsive two-column layout where width allows.
/// Takes the native `CastMember`s `BookDetailObserver` projected once, and groups them via `castGroups`.
struct CastCreditsSheet: View {
    let title: String
    let authors: [CastMember]
    let narrators: [CastMember]
    let all: [CastMember]
    let onClose: () -> Void

    /// Pushes a contributor's full page onto the main tab stack (provided by `MainTabView`). We
    /// navigate there rather than inside this sheet so a tapped cast member opens their full page.
    @Environment(\.navigateToContributor) private var navigateToContributor

    private var groups: [CastGroup] {
        castGroups(authors: authors, narrators: narrators, all: all)
    }

    private var totalCount: Int { all.count }

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
                Text(String(format: String(localized: "book.detail_credits_subtitle"), totalCount, title))
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

    /// The contributor's framed photo in a 48 pt circle, the same place in the face as on their own page;
    /// their initials on the shared `AvatarPalette` fill until it loads, or when they have none.
    private func avatar(_ member: CastMember) -> some View {
        ContributorAvatar(
            name: member.name,
            imagePath: member.imagePath,
            id: member.id,
            streamsContributorPhoto: true,
            frame: member.imageFrame
        )
        .frame(width: 48, height: 48)
        .accessibilityHidden(true)
    }
}
