import SwiftUI
import Shared

/// Presented sheet for editing a book: cover, the core metadata fields, and the relational
/// sections (authors, narrators, series, genres, tags, moods, and — for admins — collections)
/// with display, remove, and inline search-and-add pickers. Bound to `BookEditViewModel` via
/// `BookEditObserver`.
struct BookEditView: View {
    let bookId: String

    @Environment(\.dismiss) private var dismiss
    @FocusState var focusedField: BookEditFocusField?

    var body: some View {
        WithObserver(id: bookId) { deps in
            let observer = BookEditObserver(viewModel: deps.createBookEditViewModel())
            observer.loadBook(bookId: bookId)
            return observer
        } content: { observer in
            switch observer.phase {
            case .loading:
                LoadingStateView()
            case .loadFailed(let message, let canRetry):
                EditLoadFailedView(
                    message: message,
                    canRetry: canRetry,
                    onRetry: { observer.onRetryLoad() },
                    onClose: { dismiss() }
                )
            case .editing:
                editingSheet(observer)
            }
        }
    }

    private func editingSheet(_ observer: BookEditObserver) -> some View {
        EditSheetScaffold(
            title: String(localized: "book.detail_edit_book"),
            hasChanges: observer.hasChanges,
            canSave: observer.hasChanges,
            isSaving: observer.isSaving,
            onCancel: { observer.onCancel(); dismiss() },
            onSave: { observer.onSave() }
        ) {
            form(observer)
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
    }

    // MARK: - Form

    @ViewBuilder
    private func form(_ observer: BookEditObserver) -> some View {
        Section {
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
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
        }

        Section {
            identityFields(observer)
        }

        ForEach(observer.roleSections) { section in
            roleSection(section, observer: observer)
        }
        addRoleMenu(observer)

        seriesSection(observer)
        genresSection(observer)
        tagsSection(observer)
        moodsSection(observer)
        if observer.isAdmin {
            collectionsSection(observer)
        }

        Section {
            catalogFields(observer)
        }
    }
}
