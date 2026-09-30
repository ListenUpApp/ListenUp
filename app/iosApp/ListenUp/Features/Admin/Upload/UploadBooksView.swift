import SwiftUI
import Shared
import UniformTypeIdentifiers

/// Upload books from this device into the library. Reached from Administration › Management.
///
/// There is deliberately no "is this one book or three?" step. A folder keeps its shape; loose files
/// arrive flat; the server reads the tags and decides what the books are.
///
/// While files are moving the back button is hidden, because leaving would abandon them: stopping is
/// the explicit Cancel Upload, which asks first (HIG, Alerts — confirm an action that destroys work).
/// While the server imports, there is nothing to stop — cancelling would race an import already under
/// way — so the screen simply waits. Progress is a determinate bar whenever the selection reported its
/// sizes (HIG, Progress indicators: "When possible, use a determinate progress indicator").
struct UploadBooksView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss

    @State private var observer: UploadBooksObserver?
    /// Which picker the importer shows. Kept apart from `isPicking`, which the importer clears on
    /// dismissal — possibly before its completion runs — so the pick's kind survives to be read there.
    @State private var pickKind: UploadPickKind = .folder
    @State private var isPicking = false
    @State private var confirmingStop = false

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "admin.upload_books"))
        .navigationBarTitleDisplayMode(.large)
        .navigationBarBackButtonHidden(observer?.phase.isBusy ?? false)
        .onAppear {
            if observer == nil {
                observer = UploadBooksObserver(viewModel: deps.createUploadBooksViewModel())
            }
        }
    }

    // MARK: - Phases

    private func content(_ observer: UploadBooksObserver) -> some View {
        Form {
            switch observer.phase {
            case .idle:
                chooseSections(observer)
            case .uploading(let progress):
                uploadingSections(progress)
            case .finalizing:
                finalizingSection
            case .finished(let outcome):
                finishedSections(outcome, observer: observer)
            case .error(let message):
                failedSections(message, observer: observer)
            }
        }
        .readableListWidth()
        .fileImporter(
            isPresented: $isPicking,
            allowedContentTypes: pickKind == .folder ? [.folder] : [.item],
            allowsMultipleSelection: pickKind == .files
        ) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            if pickKind == .folder, let folder = urls.first {
                observer.uploadFolder(folder)
            } else {
                observer.uploadFiles(urls)
            }
        }
        .alert(
            observer.notice?.title ?? "",
            isPresented: Binding(get: { observer.notice != nil }, set: { if !$0 { observer.notice = nil } }),
            presenting: observer.notice
        ) { _ in
            Button(String(localized: "common.ok"), role: .cancel) { observer.notice = nil }
        } message: { notice in
            Text(notice.message)
        }
        .confirmationDialog(
            String(localized: "admin.upload_books_stop_title"),
            isPresented: $confirmingStop,
            titleVisibility: .visible
        ) {
            Button(String(localized: "admin.upload_books_stop_confirm"), role: .destructive) { observer.cancel() }
            Button(String(localized: "admin.upload_books_keep_uploading"), role: .cancel) {}
        } message: {
            Text(String(localized: "admin.upload_books_stop_body"))
        }
    }

    private func pick(_ kind: UploadPickKind) {
        pickKind = kind
        isPicking = true
    }

    @ViewBuilder
    private func chooseSections(_ observer: UploadBooksObserver) -> some View {
        Section {
            Text(String(localized: "admin.upload_books_description"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        Section {
            Button {
                pick(.folder)
            } label: {
                Label(String(localized: "admin.upload_books_choose_folder"), systemImage: "folder")
            }
            Button {
                pick(.files)
            } label: {
                Label(String(localized: "admin.upload_books_choose_files"), systemImage: "doc.on.doc")
            }
        } footer: {
            if observer.isPreparing {
                ProgressView()
            }
        }
        .disabled(observer.isPreparing)
    }

    @ViewBuilder
    private func uploadingSections(_ progress: UploadProgressModel) -> some View {
        Section {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(String(format: String(localized: "admin.upload_books_uploading"), progress.filename))
                    .font(.body)
                    .lineLimit(2)
                    .truncationMode(.middle)
                if let fraction = progress.fraction {
                    ProgressView(value: fraction)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Text(String(
                    format: String(localized: "admin.upload_books_file_progress"),
                    progress.fileNumber, progress.fileCount
                ))
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            .padding(.vertical, Spacing.xxs)
        } footer: {
            Text(String(localized: "admin.upload_books_keep_open"))
        }
        Section {
            Button(String(localized: "admin.upload_books_cancel"), role: .destructive) { confirmingStop = true }
        }
    }

    private var finalizingSection: some View {
        Section {
            HStack(spacing: Spacing.s) {
                ProgressView()
                Text(String(localized: "admin.upload_books_finalizing"))
            }
        } footer: {
            Text(String(localized: "admin.upload_books_keep_open"))
        }
    }

    @ViewBuilder
    private func finishedSections(_ outcome: UploadOutcomeModel, observer: UploadBooksObserver) -> some View {
        Section(String(localized: "admin.upload_books_finished_title")) {
            Label(
                String(format: String(localized: "admin.upload_books_imported"), outcome.imported),
                systemImage: "checkmark.circle.fill"
            )
            if outcome.duplicates > 0 {
                Label(
                    String(format: String(localized: "admin.upload_books_duplicates"), outcome.duplicates),
                    systemImage: "square.on.square"
                )
            }
            if outcome.failed > 0 {
                Label(
                    String(format: String(localized: "admin.upload_books_failures"), outcome.failed),
                    systemImage: "exclamationmark.triangle.fill"
                )
                .foregroundStyle(Color.luWarning)
            }
        }
        Section {
            Button(String(localized: "admin.upload_books_done")) {
                observer.reset()
                dismiss()
            }
        }
    }

    @ViewBuilder
    private func failedSections(_ message: String, observer: UploadBooksObserver) -> some View {
        Section(String(localized: "admin.upload_books_failed_title")) {
            Text(message)
        }
        Section {
            Button {
                observer.reset()
                pick(.folder)
            } label: {
                Label(String(localized: "admin.upload_books_choose_folder"), systemImage: "folder")
            }
        }
    }
}

/// Which picker is open: one folder, or any number of loose files.
private enum UploadPickKind {
    case folder
    case files
}
