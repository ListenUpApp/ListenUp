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
        ScrollView {
            MetadataSelectBody(
                preview: ready,
                region: observer.region,
                observer: observer,
                onReviewChapters: onReviewChapters,
                showChangeRow: true,
                onChange: { /* back nav handled by NavigationStack */ }
            )
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .readableWidth(720)
        }
        .safeAreaInset(edge: .bottom) {
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

/// The scrollable body of the select step (hero + grouped field lists + chapters CTA), factored
/// out so both the iPhone push screen and the iPad master–detail right column render it identically.
struct MetadataSelectBody: View {
    let preview: MetadataPreview
    let region: MetadataRegionOption
    let observer: MetadataMatchObserver
    let onReviewChapters: () -> Void
    var showChangeRow = true
    var onChange: () -> Void = {}

    private var fieldsSelectedText: String {
        let format = String(localized: "metadata.fields_selected")
        return String(format: format, preview.selectedCount, preview.totalCount)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            MetadataMatchedEditionCard(
                title: preview.title,
                regionName: region.displayName,
                coverURL: preview.coverURL,
                showChange: showChangeRow,
                onChange: onChange
            )

            HStack {
                MetadataGroupHeader(text: fieldsSelectedText)
                Spacer()
            }
            .padding(.horizontal, 4)

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

    private var coverRow: some View {
        MetadataFieldRow(
            systemImage: "photo",
            label: String(localized: "metadata.field_cover"),
            isOn: preview.coverEnabled,
            onToggle: { observer.toggleField(.cover) }
        ) {
            VStack(alignment: .leading, spacing: 2) {
                Text(preview.coverValueText).font(.callout).foregroundStyle(.primary)
                if let coverLine = coverSourceLine {
                    Text(coverLine).font(.caption2).foregroundStyle(.secondary)
                }
            }
        } thumb: {
            MetadataRemoteCover(url: preview.coverURL)
                .frame(width: 36, height: 36)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
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

    private var genresRow: some View {
        MetadataFieldRow(
            systemImage: "tag",
            label: String(localized: "metadata.field_genres"),
            isOn: preview.genres.contains { $0.isSelected },
            onToggle: { toggleAllGenres() }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataSourceChip(source: preview.genres.first?.sourceLabel)
                FlowLayout(spacing: 8) {
                    ForEach(preview.genres) { genre in
                        MetadataGenreChip(label: genre.label, isOn: genre.isSelected) {
                            observer.toggleGenre(genre.id)
                        }
                    }
                }
            }
            .padding(.top, 4)
        }
    }

    private var moodsRow: some View {
        MetadataFieldRow(
            systemImage: "theatermasks",
            label: String(localized: "metadata.field_moods"),
            isOn: preview.moods.contains { $0.isSelected },
            onToggle: { toggleAllMoods() }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataSourceChip(source: preview.moods.first?.sourceLabel)
                FlowLayout(spacing: 8) {
                    ForEach(preview.moods) { mood in
                        MetadataGenreChip(label: mood.label, isOn: mood.isSelected) {
                            observer.toggleMood(mood.id)
                        }
                    }
                }
            }
            .padding(.top, 4)
        }
    }

    private var tagsRow: some View {
        MetadataFieldRow(
            systemImage: "number",
            label: String(localized: "metadata.field_tags"),
            isOn: preview.tags.contains { $0.isSelected },
            onToggle: { toggleAllTags() }
        ) {
            VStack(alignment: .leading, spacing: 4) {
                MetadataSourceChip(source: preview.tags.first?.sourceLabel)
                FlowLayout(spacing: 8) {
                    ForEach(preview.tags) { tag in
                        MetadataGenreChip(label: tag.label, isOn: tag.isSelected) {
                            observer.toggleTag(tag.id)
                        }
                    }
                }
            }
            .padding(.top, 4)
        }
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
                            .font(.footnote).foregroundStyle(Color.luLabel2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.luLabel3)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressScaleButtonStyle())
        }
    }

    private func chapterMismatchSection(local: Int, audible: Int) -> some View {
        section(String(localized: "metadata.section_chapters")) {
            HStack(spacing: 13) {
                IconTile(systemImage: "list.number", isActive: false)
                Text(String(format: String(localized: "metadata.chapters_count_mismatch"), audible, local))
                    .font(.footnote).foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }

    private var chapterCountText: String {
        guard case .available(let available) = preview.chapters else { return "" }
        return String(format: String(localized: "metadata.chapters_matched"), available.totalCount)
    }

    /// The probed cover's provenance line: "{source} · {W×H}" when the resolution is known,
    /// otherwise just the source. `nil` when the cover source is unknown (hides the line).
    private var coverSourceLine: String? {
        guard let source = preview.coverSourceLabel else { return nil }
        guard let resolution = preview.coverResolution else { return source }
        return String(format: String(localized: "metadata.cover_source_resolution"), source, resolution)
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

    @ViewBuilder
    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        let built = content()
        VStack(alignment: .leading, spacing: 8) {
            MetadataGroupHeader(text: title).padding(.leading, 4)
            FieldGroup([0], id: \.self) { _ in
                VStack(spacing: 0) { built }
            }
        }
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
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 5) {
                    Image(systemName: "globe").font(.caption2.weight(.semibold))
                    Text(String(format: String(localized: "metadata.audible_source"), regionName))
                        .font(.caption2.weight(.bold))
                }
                .foregroundStyle(Color.luTint)
                .padding(.horizontal, 8).padding(.vertical, 3)
                .background(Capsule().fill(Color.luTint.opacity(0.14)))

                Text(title).font(.callout.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(Color.luSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.luSeparator, lineWidth: 0.5))
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
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(Color.luFill))
        }
    }
}

/// A single genre opt-in chip: a coral check + label when on, neutral when off.
struct MetadataGenreChip: View {
    let label: String
    let isOn: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 5) {
                Image(systemName: isOn ? "checkmark" : "plus").font(.caption2.weight(.bold))
                Text(label).font(.caption.weight(.semibold))
            }
            .foregroundStyle(isOn ? Color.luTint : Color.luLabel2)
            .padding(.horizontal, 11).padding(.vertical, 6)
            .background(Capsule().fill(isOn ? Color.luTint.opacity(0.13) : Color.luFill))
        }
        .buttonStyle(PressScaleButtonStyle(scale: .chip))
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

    private var mergedFromText: String? {
        guard contributingSources.count > 1 else { return nil }
        return String(format: String(localized: "metadata.merged_from"), contributingSources.joined(separator: ", "))
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
                PrimaryButton(
                    title: title,
                    icon: "checkmark",
                    isLoading: isApplying,
                    action: action
                )
                .disabled(!isEnabled)
                .opacity(isEnabled ? 1 : 0.5)
            }
            .padding(16)
            .readableWidth(720)
        }
        .background(.bar)
    }
}
