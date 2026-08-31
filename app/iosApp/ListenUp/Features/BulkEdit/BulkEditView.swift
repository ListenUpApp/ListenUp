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
            canSave: observer.canApply,
            isSaving: observer.isApplying,
            saveLabel: BulkEditFormatting.applyLabel(changedBookCount: observer.changedBookCount),
            onCancel: { dismiss() },
            onSave: { observer.apply() }
        ) {
            content(observer)
                .readableWidth(600)
                .frame(maxWidth: .infinity)
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

    @ViewBuilder
    private func content(_ observer: BulkEditObserver) -> some View {
        if observer.isLoading {
            LoadingStateView()
                .frame(minHeight: 220)
        } else {
            VStack(spacing: 22) {
                if let notice = BulkEditFormatting.notLoadedNotice(
                    bookCount: observer.bookCount,
                    requestedCount: observer.requestedCount
                ) {
                    notLoadedNotice(notice)
                }
                fields(observer)
                previewPanel(observer)
            }
            .padding(.horizontal)
        }
    }

    /// The books that were chosen but could not be read. Stated before the form, because a bulk edit
    /// that quietly touches fewer books than were picked offers no way to notice.
    private func notLoadedNotice(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(Color.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .fieldCard()
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
        VStack(spacing: 14) {
            AppTextField(
                placeholder: observer.publisherPlaceholder,
                text: Binding(get: { observer.publisher }, set: { observer.setPublisher($0) }),
                label: String(localized: "bulk_edit.publisher"),
                autocapitalization: .words
            )
            .fieldCard()

            AppTextField(
                placeholder: observer.yearPlaceholder,
                text: Binding(get: { observer.year }, set: { observer.setYear($0) }),
                label: String(localized: "bulk_edit.year"),
                keyboardType: .numberPad
            )
            .fieldCard()

            AppTextField(
                placeholder: observer.languagePlaceholder,
                text: Binding(get: { observer.language }, set: { observer.setLanguage($0) }),
                label: String(localized: "bulk_edit.language")
            )
            .fieldCard()
        }
    }

    /// What applying would actually do, per instruction.
    ///
    /// Each row is named as well as counted: three bare counts are honest and unusable, because the
    /// one instruction the user wants to reconsider is not identifiable among them. An untouched
    /// form says so in words — an empty panel would read as a broken preview.
    @ViewBuilder
    private func previewPanel(_ observer: BulkEditObserver) -> some View {
        if observer.preview.isEmpty {
            Text(String(localized: "bulk_edit.nothing_to_do"))
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            VStack(spacing: 8) {
                ForEach(observer.preview) { line in
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text(line.label)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(line.detail)
                    }
                    .font(.subheadline)
                    .foregroundStyle(line.changesNothing ? Color.luLabel2 : Color.primary)
                }
            }
            .padding(14)
            .fieldCard()
        }
    }
}
