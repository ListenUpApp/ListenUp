import SwiftUI
import Shared

/// Series and people: the two relations that are searched rather than listed.
///
/// These **add** to what each book already carries — they never replace. That is why they look
/// different from the publishing fields above them: there is no value the selection can be said to
/// share, so there is no placeholder to show and nothing to warn about overwriting.
struct BulkEditCredits: View {
    let observer: BulkEditObserver

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 10) {
                fieldLabel(String(localized: "bulk_edit.series"))
                RelationSearchField(
                    placeholder: String(localized: "bulk_edit.search_series"),
                    query: observer.seriesQuery,
                    results: observer.seriesResults,
                    isLoading: false,
                    allowsCreate: true,
                    onQueryChange: { observer.setSeriesQuery($0) },
                    onSelect: { observer.pickSeries($0) },
                    onCreate: { observer.createSeries(named: $0) }
                )
                RelationChipRow(chips: observer.seriesChips) { _ in observer.removeSeries() }
            }

            VStack(alignment: .leading, spacing: 10) {
                fieldLabel(String(localized: "bulk_edit.contributors"))
                BulkRolePicker(observer: observer)
                RelationSearchField(
                    placeholder: String(localized: "bulk_edit.search_contributors"),
                    query: observer.contributorQuery,
                    results: observer.contributorResults,
                    isLoading: false,
                    allowsCreate: true,
                    onQueryChange: { observer.setContributorQuery($0) },
                    onSelect: { observer.pickContributor($0) },
                    onCreate: { observer.createContributor(named: $0) }
                )
                RelationChipRow(chips: observer.contributorChips) { observer.removeContributor($0) }
            }
        }
    }
}

/// Genres, tags and moods: the three the library hands over whole.
///
/// None offers creation. A bulk edit that could mint a genre forty books at a time is how a library
/// ends up with `found-family`, `Found Family` and `found family` as three different things; and a
/// brand-new tag or mood needs the server, so offering one here would be a field that works only
/// while online, on a screen whose whole point is that it does not lie about what it will do.
struct BulkEditClassification: View {
    let observer: BulkEditObserver

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            section(
                LocalRelationField(
                    label: String(localized: "bulk_edit.genres"),
                    placeholder: String(localized: "bulk_edit.search_genres"),
                    query: observer.genreQuery,
                    setQuery: { observer.genreQuery = $0 },
                    offers: observer.genreOffers,
                    chips: observer.genreChips,
                    onSelect: { observer.pickGenre($0) },
                    onRemove: { observer.removeGenre($0) }
                )
            )
            section(
                LocalRelationField(
                    label: String(localized: "bulk_edit.tags"),
                    placeholder: String(localized: "bulk_edit.search_tags"),
                    query: observer.tagQuery,
                    setQuery: { observer.tagQuery = $0 },
                    offers: observer.tagOffers,
                    chips: observer.tagChips,
                    onSelect: { observer.pickTag($0) },
                    onRemove: { observer.removeTag($0) }
                )
            )
            section(
                LocalRelationField(
                    label: String(localized: "bulk_edit.moods"),
                    placeholder: String(localized: "bulk_edit.search_moods"),
                    query: observer.moodQuery,
                    setQuery: { observer.moodQuery = $0 },
                    offers: observer.moodOffers,
                    chips: observer.moodChips,
                    onSelect: { observer.pickMood($0) },
                    onRemove: { observer.removeMood($0) }
                )
            )
        }
    }

    @ViewBuilder
    private func section(_ field: LocalRelationField) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            fieldLabel(field.label)
            RelationSearchField(
                placeholder: field.placeholder,
                query: field.query,
                // Offer nothing until something is typed. The Compose editor learned on a device
                // that a blank query returning the whole library leaves the dropdown permanently
                // open over the rest of the form.
                results: field.query.trimmingCharacters(in: .whitespaces).isEmpty ? [] : field.offers,
                isLoading: false,
                allowsCreate: false,
                onQueryChange: field.setQuery,
                onSelect: field.onSelect,
                onCreate: nil
            )
            RelationChipRow(chips: field.chips, onRemove: field.onRemove)
        }
    }
}

/// One locally-filtered relation field, bundled so the three of them read as three of the same
/// thing rather than three long argument lists.
private struct LocalRelationField {
    let label: String
    let placeholder: String
    let query: String
    let setQuery: (String) -> Void
    let offers: [RelationSearchResult]
    let chips: [EditableRelation]
    let onSelect: (RelationSearchResult) -> Void
    let onRemove: (EditableRelation) -> Void
}

/// The heading above one relation field, so all five read as siblings.
private func fieldLabel(_ text: String) -> some View {
    Text(text)
        .font(.subheadline.weight(.semibold))
        .frame(maxWidth: .infinity, alignment: .leading)
}

/// The role the next credit is filed under.
///
/// A `Menu` rather than a segmented control: ten roles do not fit across a phone, and nine of them
/// are rare. Reads `allRoleApiValues` rather than a list kept here, so a role added to the contract
/// appears without anyone remembering this file.
private struct BulkRolePicker: View {
    let observer: BulkEditObserver

    var body: some View {
        HStack(spacing: 8) {
            Text(String(localized: "bulk_edit.role"))
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
            Menu {
                ForEach(BookEditObserver.allRoleApiValues, id: \.self) { apiValue in
                    Button(BookEditObserver.roleTitle(roleApiValue: apiValue)) {
                        observer.pendingRoleApiValue = apiValue
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(BookEditObserver.roleTitle(roleApiValue: observer.pendingRoleApiValue))
                    Image(systemName: "chevron.up.chevron.down").font(.caption2)
                }
                .font(.subheadline)
            }
            Spacer(minLength: 0)
        }
    }
}

/// What a relation field has collected, with a way to take each one back off.
private struct RelationChipRow: View {
    let chips: [EditableRelation]
    let onRemove: (EditableRelation) -> Void

    var body: some View {
        if chips.isEmpty {
            // An untouched field says so in words. Blank space reads as a field that failed to load
            // rather than one nobody has used.
            Text(String(localized: "bulk_edit.relation_untouched"))
                .font(.caption)
                .foregroundStyle(Color.luLabel3)
        } else {
            FlowLayout(spacing: 8) {
                ForEach(chips) { chip in
                    Button { onRemove(chip) } label: {
                        HStack(spacing: 5) {
                            Text(chip.label).font(.subheadline)
                            Image(systemName: "xmark").font(.caption2.weight(.semibold))
                        }
                        .padding(.horizontal, 11)
                        .padding(.vertical, 6)
                        .background(Color.luTint.opacity(0.12), in: Capsule())
                        .foregroundStyle(Color.luTint)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(
                        String(format: String(localized: "bulk_edit.remove_from_edit"), chip.label)
                    )
                }
            }
        }
    }
}
