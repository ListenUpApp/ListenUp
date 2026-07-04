import SwiftUI
import Shared

/// Presented sheet for editing a book: cover, the core metadata fields, and the
/// relational sections (authors, narrators, series, genres, tags, moods) with display,
/// remove, and inline search-and-add pickers. Bound to `BookEditViewModel` via
/// `BookEditObserver`.
struct BookEditView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: BookEditObserver?

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: String(localized: "book.detail_edit_book"),
                    canSave: observer.hasChanges,
                    isSaving: observer.isSaving,
                    onCancel: { observer.onCancel(); dismiss() },
                    onSave: { observer.onSave() }
                ) {
                    form(observer)
                        .readableWidth(600)
                        .frame(maxWidth: .infinity)
                }
                .alert(
                    String(localized: "common.error"),
                    isPresented: Binding(get: { observer.error != nil }, set: { _ in observer.onDismissError() })
                ) {
                    Button(String(localized: "common.ok"), role: .cancel) { observer.onDismissError() }
                } message: {
                    Text(observer.error ?? "")
                }
                .onChange(of: observer.didFinish) { _, finished in if finished { dismiss() } }
            } else {
                LoadingStateView()
            }
        }
        .task(id: bookId) {
            guard observer == nil else { return }
            let obs = BookEditObserver(viewModel: deps.createBookEditViewModel())
            observer = obs
            obs.loadBook(bookId: bookId)
        }
    }

    // MARK: - Form

    @ViewBuilder
    private func form(_ observer: BookEditObserver) -> some View {
        VStack(spacing: 22) {
            ImageEditHeader(
                shape: .rounded,
                size: 120,
                isUploading: observer.isUploadingCover,
                canRemove: false,
                onPicked: { observer.onCoverPicked($0) },
                onRemove: {}
            ) {
                BookCoverImage(coverPath: observer.displayCoverPath, coverHash: observer.coverHash)
            }
            .padding(.top, 8)

            textFields(observer)

            contributorSection(
                title: String(localized: "book.edit_authors"),
                empty: String(localized: "book.edit_no_authors"),
                contributors: observer.authors,
                role: .author,
                observer: observer
            )

            contributorSection(
                title: String(localized: "book.edit_narrators"),
                empty: String(localized: "book.edit_no_narrators"),
                contributors: observer.narrators,
                role: .narrator,
                observer: observer
            )

            seriesSection(observer)
            genresSection(observer)
            tagsSection(observer)
            moodsSection(observer)
        }
        .padding(.horizontal)
    }

    @ViewBuilder
    private func textFields(_ observer: BookEditObserver) -> some View {
        VStack(spacing: 14) {
            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.title }, set: { observer.setTitle($0) }),
                label: String(localized: "book.edit_title_field")
            )
            .fieldCard()

            AppTextField(
                placeholder: String(localized: "book.edit_add_subtitle"),
                text: Binding(get: { observer.subtitle }, set: { observer.setSubtitle($0) }),
                label: String(localized: "book.edit_subtitle")
            )
            .fieldCard()

            AppTextField(
                placeholder: String(localized: "book.edit_eg_lord_of_the_rings"),
                text: Binding(get: { observer.sortTitle }, set: { observer.setSortTitle($0) }),
                label: String(localized: "book.edit_sort_title")
            )
            .fieldCard()

            AppTextField(
                placeholder: String(localized: "book.edit_description_placeholder"),
                text: Binding(get: { observer.bookDescription }, set: { observer.setDescription($0) }),
                label: String(localized: "book.edit_description_label"),
                axis: .vertical
            )
            .fieldCard()

            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.publisher }, set: { observer.setPublisher($0) }),
                label: String(localized: "book.edit_publisher")
            )
            .fieldCard()

            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.publishYear }, set: { observer.setPublishYear($0) }),
                label: String(localized: "book.edit_year"),
                keyboardType: .numberPad
            )
            .fieldCard()
        }
    }

    // MARK: - Relational sections

    private func contributorSection(
        title: String,
        empty: String,
        contributors: [EditableRelation],
        role: ContributorRole,
        observer: BookEditObserver
    ) -> some View {
        let roleKind: RoleChip.Kind = role == .author ? .author : .narrator
        let isAuthor = role == .author
        return EditSection(title: title) {
            if contributors.isEmpty {
                EmptyRelationHint(text: empty)
            } else {
                ChipFlow {
                    ForEach(contributors) { contributor in
                        RemovableChip(
                            label: contributor.label,
                            roleKind: roleKind,
                            removeLabel: String(format: String(localized: "common.remove_name"), contributor.label),
                            onRemove: { observer.removeContributor(contributor, role: role) }
                        )
                    }
                }
            }
            RelationSearchField(
                placeholder: String(localized: isAuthor ? "book.edit_add_author" : "book.edit_add_narrator"),
                query: isAuthor ? observer.authorQuery : observer.narratorQuery,
                results: isAuthor ? observer.authorResults : observer.narratorResults,
                isLoading: isAuthor ? observer.authorSearching : observer.narratorSearching,
                allowsCreate: true,
                onQueryChange: { observer.setContributorQuery($0, role: role) },
                onSelect: { observer.selectContributorResult($0, role: role) },
                onCreate: { observer.enterContributor($0, role: role) }
            )
        }
    }

    @ViewBuilder
    private func seriesSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_series_label")) {
            if observer.series.isEmpty {
                EmptyRelationHint(text: String(localized: "book.edit_no_series"))
            } else {
                ChipFlow {
                    ForEach(observer.series) { entry in
                        RemovableChip(
                            label: entry.label,
                            roleKind: nil,
                            removeLabel: String(format: String(localized: "common.remove_name"), entry.label),
                            onRemove: { observer.removeSeries(entry) }
                        )
                    }
                }
            }
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
    private func genresSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_genres")) {
            if observer.genres.isEmpty {
                EmptyRelationHint(text: String(localized: "book.edit_no_genres"))
            } else {
                ChipFlow {
                    ForEach(observer.genres) { genre in
                        RemovableChip(
                            label: genre.label,
                            roleKind: nil,
                            removeLabel: String(format: String(localized: "common.remove_name"), genre.label),
                            onRemove: { observer.removeGenre(genre) }
                        )
                    }
                }
            }
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
    private func tagsSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.edit_tags")) {
            if observer.tags.isEmpty {
                EmptyRelationHint(text: String(localized: "book.edit_no_tags"))
            } else {
                ChipFlow {
                    ForEach(observer.tags) { tag in
                        RemovableChip(
                            label: tag.label,
                            roleKind: nil,
                            removeLabel: String(format: String(localized: "common.remove_name"), tag.label),
                            onRemove: { observer.removeTag(tag) }
                        )
                    }
                }
            }
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
    private func moodsSection(_ observer: BookEditObserver) -> some View {
        EditSection(title: String(localized: "book.detail_mood")) {
            if observer.moods.isEmpty {
                EmptyRelationHint(text: String(localized: "book.edit_no_moods"))
            } else {
                ChipFlow {
                    ForEach(observer.moods) { mood in
                        RemovableChip(
                            label: mood.label,
                            roleKind: nil,
                            removeLabel: String(format: String(localized: "common.remove_name"), mood.label),
                            onRemove: { observer.removeMood(mood) }
                        )
                    }
                }
            }
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

}

// MARK: - Pure formatting (unit-tested)

/// Pure label formatting for the book-edit relational chips, factored out of the
/// view so the seam is unit-testable without constructing SwiftUI or bridged Kotlin types.
enum BookEditFormatting {
    /// "Name · 1" when a sequence is present, otherwise just the series name. A
    /// blank/whitespace sequence is treated as absent.
    static func seriesLabel(name: String, sequence: String?) -> String {
        guard let sequence, !sequence.trimmingCharacters(in: .whitespaces).isEmpty else { return name }
        return "\(name) · \(sequence)"
    }

    /// Human-readable tag label derived from its slug: "found-family" → "Found Family".
    /// Mirrors the shared `EditableTag.displayName()` so the chip reads the same on every
    /// platform without depending on the Kotlin extension crossing the Swift Export seam.
    static func tagLabel(slug: String) -> String {
        slug
            .split(separator: "-", omittingEmptySubsequences: true)
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }

    /// "1 book" / "%d books" subtitle for a contributor or series search result. `nil` when the
    /// count is zero so the result row renders without a meaningless "0 books" line.
    static func bookCountSubtitle(_ count: Int) -> String? {
        guard count > 0 else { return nil }
        let format = count == 1 ? String(localized: "common.book_count") : String(localized: "common.books_count")
        return String(format: format, count)
    }

    /// A genre's parent-path context for the result subtitle, mirroring the shared
    /// `EditableGenre.parentPath`: "/fiction/fantasy/epic-fantasy" → "Fiction > Fantasy". `nil`
    /// for a top-level genre with no parent.
    static func genreParentPath(_ path: String) -> String? {
        let segments = path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/", omittingEmptySubsequences: true)
        guard segments.count > 1 else { return nil }
        return segments
            .dropLast()
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " > ")
    }
}

// MARK: - Section chrome

/// Uppercased caption header + grouped card, the edit-form section shell.
private struct EditSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(Color.luLabel2)
                .textCase(.uppercase)
                .padding(.leading, 4)
            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .fieldCard()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct EmptyRelationHint: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(Color.luLabel3)
    }
}

/// A removable token: an optional `RoleChip`-style icon, a label, and an `xmark`
/// remove button. Used for authors, narrators, series, genres, and tags.
private struct RemovableChip: View {
    let label: String
    let roleKind: RoleChip.Kind?
    let removeLabel: String
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            if let roleKind {
                Image(systemName: roleKind.icon)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.luLabel2)
            }
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(1)
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Color.luLabel2)
                    .frame(width: 20, height: 20)
                    .background(Circle().fill(Color.luFill))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(removeLabel)
        }
        .padding(.leading, 12)
        .padding(.trailing, 5)
        .padding(.vertical, 5)
        .background(Capsule().fill(Color.luFill.opacity(0.6)))
    }
}

/// A width-responsive wrapping flow for chips. `Layout` lets the chips reflow to as
/// many rows as the available width needs — right at every point on the size
/// continuum (full-screen iPad through narrow Split View).
private struct ChipFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        return layout(subviews: subviews, maxWidth: maxWidth).size
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout Void
    ) {
        let result = layout(subviews: subviews, maxWidth: bounds.width)
        for (index, offset) in result.offsets.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + offset.x, y: bounds.minY + offset.y),
                proposal: .unspecified
            )
        }
    }

    private func layout(subviews: Subviews, maxWidth: CGFloat) -> (size: CGSize, offsets: [CGPoint]) {
        var offsets: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            offsets.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            totalWidth = max(totalWidth, x - spacing)
        }

        return (CGSize(width: totalWidth, height: y + rowHeight), offsets)
    }
}
