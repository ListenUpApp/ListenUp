import SwiftUI
import Shared

/// Book Detail's Visibility section: who cannot see the book, then the collections that are the
/// reason, each a 44pt row opening that collection. Stranded is the one problem state, with two
/// fixes; "Show to All Members" does not ask first (it restores what was meant to be public).
///
/// HIG, Lists and tables: a header names the group, the footer explains it, navigating rows show a
/// chevron. HIG, Buttons: the preferred fix is the prominent one. HIG, Color: orange, not red,
/// because nothing is lost.
struct BookVisibilitySection: View {
    let model: BookVisibilityModel
    let isRestoring: Bool
    let onShowToAllMembers: () -> Void
    let onAddToCollection: () -> Void

    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "book.visibility_title"))
                .font(.headline)
                .padding(.horizontal, Spacing.xxs)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: Spacing.s) {
                switch model {
                case .restricted(let collections, let hiddenFrom):
                    restricted(collections: collections, hiddenFrom: hiddenFrom)
                case .stranded:
                    stranded
                }
            }
            .padding(Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
            Text(String(localized: "book.visibility_admins_only_footer_ios"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal, Spacing.m)
        }
    }

    @ViewBuilder
    private func restricted(collections: [VisibilityCollection], hiddenFrom: HiddenFromModel) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            Text(VisibilityCopy.headline(hiddenFrom, expanded: expanded))
                .font(.headline)
            Text(VisibilityCopy.reason(hiddenFrom, collections: collections))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        if case .members(let names) = hiddenFrom, VisibilityCopy.canExpand(names, expanded: expanded) {
            Divider()
            Button(String(format: String(localized: "book.visibility_show_all_ios"), names.count)) {
                expanded = true
            }
            .buttonStyle(.borderless)
            .frame(minHeight: 44)
        }
        ForEach(collections) { collection in
            Divider()
            NavigationLink(value: AdminCollectionDetailDestination(collectionId: collection.id)) {
                HStack(spacing: Spacing.s) {
                    Image(systemName: "rectangle.stack")
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                    Text(collection.name)
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var stranded: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(alignment: .top, spacing: Spacing.s) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: Spacing.xxs) {
                    Text(String(localized: "book.visibility_hidden_from_all"))
                        .font(.headline)
                    Text(String(localized: "book.visibility_reason_stranded"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Divider()
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Spacing.s) { fixes }
                VStack(alignment: .leading, spacing: Spacing.s) { fixes }
            }
        }
    }

    @ViewBuilder
    private var fixes: some View {
        Button(
            isRestoring
                ? String(localized: "book.visibility_restoring_ios")
                : String(localized: "book.visibility_show_to_all_ios")
        ) { onShowToAllMembers() }
            .prominentAction()
            .buttonBorderShape(.capsule)
            .disabled(isRestoring)
        Button(String(localized: "book.visibility_add_to_collection_ios")) { onAddToCollection() }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .buttonBorderShape(.capsule)
    }
}
