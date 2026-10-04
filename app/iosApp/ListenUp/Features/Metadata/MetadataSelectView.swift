import SwiftUI
import Shared

/// Step 2: choose which fields of the matched Audible edition to apply. A matched-edition hero,
/// grouped field checklists (Identity / Classification / Details), a chapters CTA, and an
/// "Apply Metadata" tray. Reused verbatim inside the iPad master–detail via `MetadataSelectBody`.
struct MetadataSelectView: View {
    let observer: MetadataMatchObserver
    let onReviewChapters: () -> Void

    var body: some View {
        Group {
            switch observer.phase {
            case .preview(let preview):
                previewContent(preview)
            default:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "metadata.select_metadata"))
        .navigationBarTitleDisplayMode(.large)
    }

    @ViewBuilder
    private func previewContent(_ preview: MetadataPreviewStatus) -> some View {
        switch preview {
        case .loading:
            LoadingStateView(label: String(localized: "metadata.loading_match"))
        case .failed(let message):
            ContentUnavailableView {
                Label(
                    String(localized: "metadata.failed_to_load_metadata_preview"),
                    systemImage: "exclamationmark.triangle"
                )
            } description: {
                Text(message)
            }
        case .ready(let ready):
            readyContent(ready)
        }
    }

    @ViewBuilder
    private func readyContent(_ ready: MetadataPreview) -> some View {
        List {
            MetadataSelectBody(
                preview: ready,
                region: observer.region,
                observer: observer,
                onReviewChapters: onReviewChapters,
                showChangeRow: true,
                onChange: { /* back nav handled by NavigationStack */ }
            )
        }
        .listStyle(.insetGrouped)
        .readableListWidth(720)
        .safeAreaBar(edge: .bottom) {
            MetadataApplyTray(
                isApplying: ready.isApplying,
                isEnabled: ready.selectedCount > 0,
                applyError: ready.applyError,
                contributingSources: ready.contributingSources,
                action: { observer.applyMatch() }
            )
        }
    }
}

/// The body of the select step (hero + grouped field sections + chapters CTA) as `List` sections,
/// factored out so both the iPhone push screen and the iPad master–detail right column render it
/// identically. Host it inside a `List`.
struct MetadataSelectBody: View {
    let preview: MetadataPreview
    let region: MetadataRegionOption
    let observer: MetadataMatchObserver
    let onReviewChapters: () -> Void
    var showChangeRow = true
    var onChange: () -> Void = {}

    var body: some View {
        Group {
            Section {
                // The matched edition is a content card (cover, source, title), not a list row.
                MetadataMatchedEditionCard(
                    title: preview.title,
                    regionName: region.displayName,
                    coverURL: preview.coverURL,
                    showChange: showChangeRow,
                    onChange: onChange
                )
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            } footer: {
                MetadataSelectionSummary(
                    selectedCount: preview.selectedCount,
                    totalCount: preview.totalCount,
                    contributingSources: preview.contributingSources
                )
            }

            section(String(localized: "metadata.section_identity")) {
                coverRow
                ForEach(preview.identityFields) { field in scalarRow(field) }
                ForEach(preview.authors) { authorRow($0) }
                ForEach(preview.narrators) { narratorRow($0) }
                ForEach(preview.seriesItems) { seriesRow($0) }
            }

            if !preview.genres.isEmpty || !preview.moods.isEmpty || !preview.tags.isEmpty {
                section(String(localized: "metadata.section_classification")) {
                    if !preview.genres.isEmpty { genresRow }
                    if !preview.moods.isEmpty { moodsRow }
                    if !preview.tags.isEmpty { tagsRow }
                }
            }

            if preview.descriptionField != nil || !preview.detailFields.isEmpty {
                section(String(localized: "metadata.section_details")) {
                    if let description = preview.descriptionField { descriptionRow(description) }
                    ForEach(preview.detailFields) { field in scalarRow(field) }
                }
            }

            if case .available = preview.chapters {
                chaptersSection
            } else if case .mismatch(let local, let audible) = preview.chapters {
                chapterMismatchSection(local: local, audible: audible)
            }
        }
    }

    // MARK: - Rows

    // A bespoke cover section (not a MetadataFieldRow — its whole body is one toggle Button, which
    // would swallow taps on the individual cover cards). A header toggles whether a new cover applies and
    // names the source of the one that will; below it the book's current cover and every candidate, with
    // exactly one marked — the cover Apply writes.
    private var coverRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: { observer.toggleField(.cover) }) {
                HStack(spacing: 12) {
                    IconTile(systemImage: "photo", isActive: preview.coverEnabled)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "metadata.field_cover"))
                            .font(.body)
                            .foregroundStyle(.primary)
                        Text(preview.appliedCoverLabel ?? String(localized: "metadata.current_cover"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    CircularCheckMark(isOn: preview.coverEnabled)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(preview.coverEnabled ? .isSelected : [])

            if !preview.coverOptions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        currentCoverCard
                        ForEach(preview.coverOptions) { coverOptionCard($0) }
                    }
                    .padding(.horizontal, 2)
                }
            }
        }
    }

    private var currentCoverCard: some View {
        coverCard(
            label: String(localized: "metadata.current_cover"),
            resolution: nil,
            isSelected: preview.keepsCurrentCover,
            action: { observer.keepCurrentCover() }
        ) {
            BookCoverImage(bookId: observer.bookId, coverPath: nil)
        }
    }

    private func coverOptionCard(_ option: MetadataCoverOption) -> some View {
        coverCard(
            label: option.label,
            resolution: option.resolution,
            isSelected: option.url == preview.appliedCoverURL,
            action: { observer.selectCover(option.url) }
        ) {
            MetadataRemoteCover(url: option.url)
        }
    }

    private func coverCard(
        label: String,
        resolution: String?,
        isSelected: Bool,
        action: @escaping () -> Void,
        @ViewBuilder art: () -> some View
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                art()
                    .frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                            .strokeBorder(isSelected ? Color.accentColor : Color.clear, lineWidth: 2.5)
                    }
                Text(label)
                    .font(.caption2)
                    .fontWeight(isSelected ? .semibold : .regular)
                    .foregroundStyle(isSelected ? .primary : .secondary)
                if let resolution {
                    Text(resolution)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
            .frame(width: 96)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : [.isButton])
    }

    private func scalarRow(_ field: MetadataFieldSelection) -> some View {
        MetadataFieldRow(
            systemImage: field.systemImage,
            label: field.label,
            isOn: field.isSelected,
            onToggle: { observer.toggleField(field.field) }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataValueText(field.value)
                MetadataSourceChip(source: field.sourceLabel)
            }
        }
    }

    private func descriptionRow(_ field: MetadataFieldSelection) -> some View {
        MetadataFieldRow(
            systemImage: field.systemImage,
            label: field.label,
            isOn: field.isSelected,
            onToggle: { observer.toggleField(field.field) }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                Text(field.value).font(.footnote).foregroundStyle(.primary).lineLimit(3)
                MetadataSourceChip(source: field.sourceLabel)
            }
        }
    }

    private func authorRow(_ author: MetadataContributorSelection) -> some View {
        MetadataFieldRow(
            systemImage: "person",
            label: String(localized: "metadata.field_authors"),
            isOn: author.isSelected,
            onToggle: { observer.toggleAuthor(author.id) }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataValueText(author.name)
                MetadataSourceChip(source: author.sourceLabel)
            }
        }
    }

    private func narratorRow(_ narrator: MetadataContributorSelection) -> some View {
        MetadataFieldRow(
            systemImage: "mic",
            label: String(localized: "metadata.field_narrators"),
            isOn: narrator.isSelected,
            onToggle: { observer.toggleNarrator(narrator.id) }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataValueText(narrator.name)
                MetadataSourceChip(source: narrator.sourceLabel)
            }
        }
    }

    private func seriesRow(_ series: MetadataSeriesSelection) -> some View {
        MetadataFieldRow(
            systemImage: "books.vertical",
            label: String(localized: "metadata.field_series"),
            isOn: series.isSelected,
            onToggle: { observer.toggleSeries(series.id) }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataValueText(series.displayText)
                MetadataSourceChip(source: series.sourceLabel)
            }
        }
    }

    // One chip and flow per source, so Hardcover's additions say where they came from (#1542).
    private var genresRow: some View {
        MetadataChipField(
            systemImage: "tag",
            label: String(localized: "metadata.field_genres"),
            runs: MetadataMatchMapping.sourceRuns(preview.genres),
            onToggleAll: { toggleAllGenres() },
            onToggle: { observer.toggleGenre($0) }
        )
    }

    private var moodsRow: some View {
        MetadataChipField(
            systemImage: "theatermasks",
            label: String(localized: "metadata.field_moods"),
            runs: MetadataMatchMapping.sourceRuns(preview.moods),
            onToggleAll: { toggleAllMoods() },
            onToggle: { observer.toggleMood($0) }
        )
    }

    /// Tags carry one field-level source, so they are a single run under one source chip.
    private var tagsRow: some View {
        MetadataChipField(
            systemImage: "number",
            label: String(localized: "metadata.field_tags"),
            runs: [MetadataSourceRun(source: preview.tags.first?.sourceLabel, items: preview.tags)],
            onToggleAll: { toggleAllTags() },
            onToggle: { observer.toggleTag($0) }
        )
    }

    private var chaptersSection: some View {
        section(String(localized: "metadata.section_chapters")) {
            Button(action: onReviewChapters) {
                HStack(spacing: 13) {
                    IconTile(systemImage: "list.number")
                    VStack(alignment: .leading, spacing: 1) {
                        Text(chapterCountText)
                            .font(.body.weight(.medium)).foregroundStyle(.primary)
                        Text(String(localized: "metadata.chapters_review_apply"))
                            .font(.footnote).foregroundStyle(Color.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.luLabel3)
                }
                .contentShape(Rectangle())
            }
            .foregroundStyle(Color.primary)
        }
    }

    private func chapterMismatchSection(local: Int, audible: Int) -> some View {
        section(String(localized: "metadata.section_chapters")) {
            HStack(spacing: 13) {
                IconTile(systemImage: "list.number", isActive: false)
                Text(String(format: String(localized: "metadata.chapters_count_mismatch"), audible, local))
                    .font(.footnote).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var chapterCountText: String {
        guard case .available(let available) = preview.chapters else { return "" }
        return String(format: String(localized: "metadata.chapters_matched"), available.totalCount)
    }

    private func toggleAllGenres() {
        // Tapping the group check flips every genre to the inverse of the current "any selected".
        let anySelected = preview.genres.contains { $0.isSelected }
        for genre in preview.genres where genre.isSelected == anySelected {
            observer.toggleGenre(genre.id)
        }
    }

    private func toggleAllMoods() {
        let anySelected = preview.moods.contains { $0.isSelected }
        for mood in preview.moods where mood.isSelected == anySelected {
            observer.toggleMood(mood.id)
        }
    }

    private func toggleAllTags() {
        let anySelected = preview.tags.contains { $0.isSelected }
        for tag in preview.tags where tag.isSelected == anySelected {
            observer.toggleTag(tag.id)
        }
    }

    // MARK: - Section scaffold

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        Section(title) { content() }
    }
}

/// The matched-edition hero card: cover, an "Audible · {region}" badge, the title, and an optional
/// "Change" link back to search.
struct MetadataMatchedEditionCard: View {
    let title: String
    let regionName: String
    let coverURL: String?
    var showChange: Bool = true
    var onChange: () -> Void = {}

    var body: some View {
        HStack(spacing: 14) {
            MetadataRemoteCover(url: coverURL)
                .frame(width: 58, height: 58)
                .clipShape(RoundedRectangle(cornerRadius: Radius.m, style: .continuous))

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 5) {
                    Image(systemName: "globe").font(.caption2.weight(.semibold))
                    Text(String(format: String(localized: "metadata.audible_source"), regionName))
                        .font(.caption2.weight(.bold))
                }
                .foregroundStyle(.secondary)
                .padding(.horizontal, Spacing.xs).padding(.vertical, 3)
                .background(Capsule().fill(Color.luFill))

                Text(title).font(.callout.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(Spacing.m)
        .background(Color.luSurface2)
        .clipShape(RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.l, style: .continuous).stroke(Color.luSeparator, lineWidth: 0.5))
    }
}

/// A small "from {source}" provenance capsule shown under a field whose value fell back to a
/// non-primary provider. Renders nothing when `source` is `nil`, so callers can pass an optional
/// straight through. `luFill` (not `luSurface2`) keeps it visible against the row's grouped
/// surface. Uses the semantic `.caption2` font so it scales with Dynamic Type.
struct MetadataSourceChip: View {
    let source: String?

    var body: some View {
        if let source {
            Text(String(format: String(localized: "metadata.field_source"), source))
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, Spacing.xs).padding(.vertical, 2)
                // A capsule on one line; a rounded card once a large size wraps it.
                .background(RoundedRectangle(cornerRadius: Radius.m, style: .continuous).fill(Color.luFill))
        }
    }
}

/// A single genre opt-in chip: a coral check + label when on, neutral when off.
///
/// Its target is 44 points tall around the drawn capsule (HIG, Accessibility: "at least 44x44 pt"), and at
/// large sizes its label wraps inside the width `FlowLayout` offers rather than running off the card. A chip
/// from a fallback provider names its `source` for VoiceOver ("Fantasy, from Hardcover"). The glyph is hidden:
/// `checkmark`'s own label is "Selected", which the trait already says.
struct MetadataGenreChip: View {
    let label: String
    let isOn: Bool
    var source: String?
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 5) {
                Image(systemName: isOn ? "checkmark" : "plus").font(.caption2.weight(.bold))
                    .accessibilityHidden(true)
                Text(label).font(.caption.weight(.semibold))
                    .multilineTextAlignment(.leading)
            }
            .foregroundStyle(isOn ? Color.luTint : Color.secondary)
            .padding(.horizontal, Spacing.s).padding(.vertical, Spacing.xs)
            // A capsule on one line; a rounded card once a large size wraps the label.
            .background(
                RoundedRectangle(cornerRadius: Radius.l, style: .continuous)
                    .fill(isOn ? Color.luTint.opacity(0.13) : Color.luFill)
            )
            .frame(minHeight: TapTarget.minimum)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle(scale: .chip))
        .accessibilityLabel(source.map { String(format: String(localized: "metadata.chip_from_source"), label, $0) }
            ?? label)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// The sticky bottom apply tray: an inline error caption above a full-width primary button. The
/// `title` lets the same tray serve both "Apply Metadata" (select step) and "Apply Chapter Names".
struct MetadataApplyTray: View {
    var title = String(localized: "metadata.apply_metadata")
    let isApplying: Bool
    let isEnabled: Bool
    let applyError: String?
    /// Providers that contributed a winning field. More than one drives the "Merged from …"
    /// footer above the apply button. Empty (the chapter-apply reuse) hides it.
    var contributingSources: [String] = []
    let action: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// "Merged from Audible, Hardcover" when more than one provider contributed.
    static func mergedFromText(_ sources: [String]) -> String? {
        guard sources.count > 1 else { return nil }
        return String(format: String(localized: "metadata.merged_from"), sources.joined(separator: ", "))
    }

    /// At the accessibility sizes the line and a two-line button filled ~45% of the screen over the fields
    /// being reviewed, so the line moves into the list (`MetadataSelectionSummary`) and the bar keeps only
    /// the button. HIG, Layout: keep primary content visible.
    private var mergedFromText: String? {
        dynamicTypeSize.isAccessibilitySize ? nil : Self.mergedFromText(contributingSources)
    }

    var body: some View {
        VStack(spacing: 0) {
            Divider()
            VStack(spacing: 8) {
                if let mergedFromText {
                    Text(mergedFromText).font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let applyError {
                    Text(applyError).font(.caption).foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button(action: action) {
                    ActionLabel(title: title, systemImage: "checkmark", isBusy: isApplying)
                        // A bar over the content stays compact, as the system's bars do: past the first
                        // accessibility size a long press shows the label in the large content viewer.
                        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                }
                .prominentAction()
                .accessibilityShowsLargeContentViewer()
                .disabled(isApplying || !isEnabled)
            }
            .padding(Spacing.m)
            .readableWidth(720)
        }
    }
}
