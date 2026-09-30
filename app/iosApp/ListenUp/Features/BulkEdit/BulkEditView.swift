import SwiftUI

/// Bulk metadata editing for a selection of books, presented as a sheet.
///
/// A sheet rather than a pushed screen because `EditSheetScaffold` brings its own `NavigationStack`
/// and every other edit surface in the app is one — and because the selection toolbar is a
/// `ViewModifier` with no `NavigationPath` to push onto.
///
/// Applying is a **local** operation: every repository underneath writes Room-first and enqueues an
/// outbox row in one transaction, so it completes at disk speed and the server outcome belongs to
/// the sync engine. That is why there is no progress bar and no "37 of 40 succeeded" report — at the
/// moment Apply returns, the server has not been consulted.
struct BulkEditView: View {
    /// The selected books. Fixed for the sheet's lifetime — the editor never switches selection.
    let bookIds: [String]
    /// Called with the number of books that actually changed, once an apply succeeds.
    var onApplied: ((Int) -> Void)?

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: BulkEditObserver?

    var body: some View {
        Group {
            if let observer {
                sheet(observer)
            } else {
                LoadingStateView()
            }
        }
        .task(id: bookIds) {
            guard observer == nil else { return }
            observer = BulkEditObserver(viewModel: deps.createBulkEditViewModel(bookIds: bookIds))
        }
    }

    // MARK: - Chrome

    @ViewBuilder
    private func sheet(_ observer: BulkEditObserver) -> some View {
        EditSheetScaffold(
            // Silent until the selection is read: a title counting books nobody has loaded yet
            // would be the sheet's first untrue statement.
            title: observer.isLoading ? "" : BulkEditFormatting.title(bookCount: observer.bookCount),
            hasChanges: observer.changedBookCount > 0,
            canSave: observer.canApply,
            isSaving: observer.isApplying,
            saveLabel: BulkEditFormatting.applyLabel(changedBookCount: observer.changedBookCount),
            onCancel: { dismiss() },
            onSave: { observer.apply() }
        ) {
            content(observer)
        }
        .alert(
            String(localized: "common.error"),
            isPresented: Binding(get: { observer.error != nil }, set: { _ in observer.dismissError() })
        ) {
            Button(String(localized: "common.ok"), role: .cancel) { observer.dismissError() }
        } message: {
            Text(observer.error ?? "")
        }
        .onChange(of: observer.didFinish) { _, finished in
            guard finished else { return }
            onApplied?(observer.appliedCount)
            dismiss()
        }
    }

    // MARK: - Body

    /// Grouped `Form` sections: the hero on the plain background, then Publishing, Credits and
    /// Classification — each group's title and promise heading its first section — and the preview.
    @ViewBuilder
    private func content(_ observer: BulkEditObserver) -> some View {
        if observer.isLoading {
            Section {
                LoadingStateView()
                    .frame(minHeight: 220)
                    .listRowBackground(Color.clear)
            }
        } else {
            Section {
                BulkEditHero(
                    covers: observer.selectionCovers,
                    bookCount: observer.bookCount,
                    selectedCount: observer.requestedCount
                )
                .listRowBackground(Color.clear)
                if let notice = BulkEditFormatting.notLoadedNotice(
                    bookCount: observer.bookCount,
                    requestedCount: observer.requestedCount
                ) {
                    // The books that were chosen but could not be read. Stated before the form,
                    // because a bulk edit that quietly touches fewer books than were picked offers
                    // no way to notice.
                    Text(notice)
                        .font(.subheadline)
                        .foregroundStyle(Color.primary)
                }
            }

            Section {
                fields(observer)
            } header: {
                BulkEditGroupHeader(
                    title: String(localized: "bulk_edit.card_publishing"),
                    note: String(localized: "bulk_edit.card_publishing_note")
                )
            }

            BulkEditCredits(observer: observer)
            BulkEditClassification(observer: observer)

            previewSection(observer)
        }
    }

    /// The three text fields.
    ///
    /// Every field starts **empty**, and empty means "do not touch". Where the selection already
    /// agrees on a value it appears as placeholder text; where books differ the placeholder reads
    /// "Multiple values". Placeholder, never value — that is what keeps "the user typed something"
    /// and "this field produces an instruction" the same fact, so Apply cannot rewrite a field
    /// nobody touched.
    @ViewBuilder
    private func fields(_ observer: BulkEditObserver) -> some View {
        Group {
            publishingField(
                placeholder: observer.publisherPlaceholder,
                text: Binding(get: { observer.publisher }, set: { observer.setPublisher($0) }),
                entry: .words,
                label: String(localized: "bulk_edit.publisher"),
                consequence: observer.consequences[.publisher]
            )
            publishingField(
                placeholder: observer.yearPlaceholder,
                text: Binding(get: { observer.year }, set: { observer.setYear($0) }),
                entry: .number,
                label: String(localized: "bulk_edit.year"),
                consequence: observer.consequences[.year]
            )
            publishingField(
                placeholder: observer.languagePlaceholder,
                text: Binding(get: { observer.language }, set: { observer.setLanguage($0) }),
                entry: .words,
                label: String(localized: "bulk_edit.language"),
                consequence: observer.consequences[.language]
            )
        }
    }

    /// One publishing field and, under it, the sentence saying what Apply would do to it. Clearable,
    /// because a typed value arms an instruction and emptying the field is how it is disarmed.
    private func publishingField(
        placeholder: String,
        text: Binding<String>,
        entry: TextEntry,
        label: String,
        consequence: FieldConsequence?
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            AppTextField(placeholder: placeholder, text: text, entry: entry, label: label, clearable: true)
            BulkEditConsequenceLine(consequence: consequence)
        }
    }

    /// What applying would actually do, per instruction.
    ///
    /// Each row is named as well as counted: three bare counts are honest and unusable, because the
    /// one instruction the user wants to reconsider is not identifiable among them. The bar is the
    /// count again in a form nobody has to count. An untouched form says so in words — an empty
    /// panel would read as a broken preview.
    @ViewBuilder
    private func previewSection(_ observer: BulkEditObserver) -> some View {
        Section(String(localized: "bulk_edit.card_preview")) {
            if observer.preview.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "bulk_edit.nothing_to_do"))
                        .font(.subheadline)
                        .foregroundStyle(Color.primary)
                    Text(String(localized: "bulk_edit.nothing_to_do_hint"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ForEach(observer.preview) { line in
                    BulkEditPreviewRow(line: line)
                }
            }
        }
    }
}

/// The heading over one group of the bulk form: the group's name, and under it the promise the
/// whole screen rests on — these fields *add* to what each book already carries, and a field nobody
/// touches writes to nothing. An optional `field` names the section's own field beneath.
struct BulkEditGroupHeader: View {
    let title: String
    let note: String
    var field: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.headline)
                .foregroundStyle(.primary)
                .textCase(nil)
            Text(note)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textCase(nil)
            if let field {
                Text(field)
                    .padding(.top, Spacing.xs)
            }
        }
    }
}

/// One instruction in the preview: what it changes, how much of the selection that is, and what it
/// leaves behind. A row that changes nothing is dimmed rather than hidden.
private struct BulkEditPreviewRow: View {
    let line: BulkEditPreviewLine

    var body: some View {
        let accent = line.changesNothing ? Color.secondary : Color.luTint
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: line.symbol)
                .font(.body)
                .foregroundStyle(accent)
                .frame(width: 28, height: 28)
                .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(line.label)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(line.changesNothing ? Color.secondary : Color.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(line.detail)
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(accent)
                }
                // A proportion of a whole, not a job in flight — no animation, no stop mark.
                ProgressView(value: line.fraction)
                    .progressViewStyle(.linear)
                    .tint(accent)
                if let note = line.note {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The sentence under a field that says what Apply would do to it. Loud only when something will
/// actually be written; nothing is rendered at all when the field has no consequence to state.
struct BulkEditConsequenceLine: View {
    let consequence: FieldConsequence?

    var body: some View {
        if let consequence {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Image(systemName: consequence.symbol)
                    .font(.caption2.weight(.semibold))
                Text(consequence.text)
                    .font(.caption.weight(consequence.writes ? .bold : .medium))
            }
            .foregroundStyle(consequence.writes ? Color.luTint : Color.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

/// The selection's covers, overlapped like a hand of cards, with the remainder as a chip and where
/// the selection came from above. The covers are a sample; the chip names the rest.
private struct BulkEditHero: View {
    let covers: [CoverArt]
    let bookCount: Int
    let selectedCount: Int

    private let shown = 4

    var body: some View {
        if !covers.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text(BulkEditFormatting.heroEyebrow(selectedCount: selectedCount))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                HStack(spacing: 12) {
                    CoverStack(covers: covers, size: 64, peek: 16, maxCovers: shown)
                    if let more = BulkEditFormatting.heroMore(bookCount: bookCount, shown: min(covers.count, shown)) {
                        Text(more)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, Spacing.s)
                            .padding(.vertical, Spacing.xxs)
                            .background(Color.luFill, in: Capsule())
                    }
                    Spacer(minLength: 0)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}
