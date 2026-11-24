import SwiftUI

/// Relational sections for `BookEditView`: contributor roles (and the Add Role menu), series,
/// genres, tags, moods, and — for admins — collections.
extension BookEditView {
    /// One dynamic contributor-role section (Author, Narrator, Editor, …), driven entirely by the
    /// shared VM's visible roles via `observer.roleSections`. Any role but Author can be removed.
    func roleSection(_ section: BookEditRoleSection, observer: BookEditObserver) -> some View {
        EditSection(title: section.title) {
            RelationChipGroup(
                items: section.contributors,
                emptyText: String(localized: "book.edit_no_contributors"),
                roleKind: Self.roleChipKind(section.id),
                onRemove: { observer.removeContributor($0, roleApiValue: section.id) }
            )
            RelationSearchField(
                placeholder: String(localized: "book.edit_add_contributor"),
                query: section.query,
                results: section.results,
                isLoading: section.searching,
                allowsCreate: true,
                onQueryChange: { observer.setContributorQuery($0, roleApiValue: section.id) },
                onSelect: { observer.selectContributorResult($0, roleApiValue: section.id) },
                onCreate: { observer.enterContributor($0, roleApiValue: section.id) }
            )
            if section.canRemove {
                Button {
                    observer.removeRole(roleApiValue: section.id)
                } label: {
                    Label(
                        String(localized: .commonRemoveName(section.title)),
                        systemImage: "minus.circle"
                    )
                    .font(.caption)
                    .foregroundStyle(Color.luLabel3)
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// The "Add role" menu — lists every role not currently shown, adding a section on tap.
    @ViewBuilder
    func addRoleMenu(_ observer: BookEditObserver) -> some View {
        if !observer.addableRoles.isEmpty {
            Section {
                Menu {
                    ForEach(observer.addableRoles) { addable in
                        Button(addable.title) { observer.addRole(roleApiValue: addable.id) }
                    }
                } label: {
                    Label(String(localized: "book.edit_add_role"), systemImage: "plus.circle")
                }
            }
        }
    }

    /// Author/Narrator keep their role glyph; the other roles render without one (like series/genres).
    /// Keyed by the role's `apiValue` string — Swift never handles a bridged `ContributorRole` value.
    private static func roleChipKind(_ apiValue: String) -> RoleChip.Kind? {
        switch apiValue {
        case "author": return .author
        case "narrator": return .narrator
        default: return nil
        }
    }

    @ViewBuilder
    func seriesSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_series_label")) {
            RelationChipGroup(
                items: observer.series,
                emptyText: String(localized: "book.edit_no_series"),
                onRemove: { observer.removeSeries($0) }
            )
            RelationSearchField(
                placeholder: String(localized: "book.edit_add_series"),
                query: observer.seriesQuery,
                results: observer.seriesResults,
                isLoading: observer.seriesSearching,
                allowsCreate: true,
                onQueryChange: { observer.setSeriesQuery($0) },
                onSelect: { observer.selectSeriesResult($0) },
                onCreate: { observer.enterSeries($0) }
            )
        }
    }

    @ViewBuilder
    func genresSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_genres")) {
            RelationChipGroup(
                items: observer.genres,
                emptyText: String(localized: "book.edit_no_genres"),
                onRemove: { observer.removeGenre($0) }
            )
            RelationSearchField(
                placeholder: String(localized: "book.edit_add_genre"),
                query: observer.genreQuery,
                results: observer.genreResults,
                isLoading: false,
                allowsCreate: false,
                onQueryChange: { observer.setGenreQuery($0) },
                onSelect: { observer.selectGenreResult($0) },
                onCreate: nil
            )
        }
    }

    @ViewBuilder
    func tagsSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_tags")) {
            RelationChipGroup(
                items: observer.tags,
                emptyText: String(localized: "book.edit_no_tags"),
                onRemove: { observer.removeTag($0) }
            )
            RelationSearchField(
                placeholder: String(localized: "book.edit_add_tag"),
                query: observer.tagQuery,
                results: observer.tagResults,
                isLoading: observer.tagSearching,
                allowsCreate: true,
                onQueryChange: { observer.setTagQuery($0) },
                onSelect: { observer.selectTagResult($0) },
                onCreate: { observer.enterTag($0) }
            )
        }
    }

    @ViewBuilder
    func moodsSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.detail_mood")) {
            RelationChipGroup(
                items: observer.moods,
                emptyText: String(localized: "book.edit_no_moods"),
                onRemove: { observer.removeMood($0) }
            )
            RelationSearchField(
                placeholder: String(localized: "book.edit_add_mood"),
                query: observer.moodQuery,
                results: observer.moodResults,
                isLoading: observer.moodSearching,
                allowsCreate: true,
                onQueryChange: { observer.setMoodQuery($0) },
                onSelect: { observer.selectMoodResult($0) },
                onCreate: { observer.enterMood($0) }
            )
        }
    }

    /// Admin-only: attach the book to existing collections (select-only, no free-text creation —
    /// same shape as genres). Persisted on Save via the shared VM's `setBookCollections` RPC. This is
    /// the sanctioned place to assign collections before releasing an inbox book.
    @ViewBuilder
    func collectionsSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_collections")) {
            if BookEditObserver.warnsCollectionsRelease(isAdmin: observer.isAdmin, isHeld: observer.isHeld) {
                // Amber tray and words — never colour alone (one line).
                Label {
                    Text(String(localized: "admin.held_collections_release"))
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "tray.full")
                        .foregroundStyle(Color.luWarning)
                }
                .font(.footnote)
            }
            RelationChipGroup(
                items: observer.collections,
                emptyText: String(localized: "book.edit_no_collections"),
                onRemove: { observer.removeCollection($0) }
            )
            RelationSearchField(
                placeholder: String(localized: "book.edit_add_collection"),
                query: observer.collectionQuery,
                results: observer.collectionResults,
                isLoading: false,
                allowsCreate: false,
                onQueryChange: { observer.setCollectionQuery($0) },
                onSelect: { observer.selectCollectionResult($0) },
                onCreate: nil
            )
        }
    }
}
