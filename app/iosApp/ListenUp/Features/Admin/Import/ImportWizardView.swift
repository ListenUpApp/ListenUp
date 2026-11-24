import SwiftUI
import Shared

/// The Audiobookshelf import wizard, presented as a sheet from the import hub. A linear pipeline
/// bound to `ImportFlowObserver`: **Intro** (choose a backup file) → **Uploading** → **Analyzing**
/// → **Review users** → **Applying** → **Complete**. A failure surfaces inline with a retry.
///
/// Native iOS chrome: a `NavigationStack` with a large title and a Cancel/Done toolbar; the file
/// picker is a SwiftUI `.fileImporter`. The flow is destructive (no step-back) — matching the
/// shared `ImportFlowViewModel` — so the only navigation is forward or dismiss.
///
/// Responsive: every step is a grouped `List` in a `.readableListWidth()` column that stays honest
/// on iPhone and grows gracefully on iPad and wide split views (rule 12).
struct ImportWizardView: View {
    @Environment(\.dismiss) private var dismiss

    /// Called once the import completes successfully, so the hub can refresh its roster.
    var onCompleted: () -> Void = {}

    @State private var showingFileImporter = false
    @State private var pickError: String?
    @State private var assigningUser: ImportUserRowModel?

    var body: some View {
        WithObserver { ImportFlowObserver(viewModel: $0.createImportFlowViewModel()) } content: { observer in
            NavigationStack {
                content(observer: observer)
                    .background(Color.luSurface)
                    .navigationTitle(navigationTitle(for: observer))
                    // Inline, not large: this is a sheet with a leading Cancel button, and a large
                    // title's vertical span collides with that button on narrow widths. Inline is also
                    // the HIG-standard chrome for a modal sheet.
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { toolbar(observer) }
                    .fileImporter(
                        isPresented: $showingFileImporter,
                        allowedContentTypes: ImportFileSourceBridge.allowedContentTypes
                    ) { result in
                        handlePickResult(result, observer: observer)
                    }
                    .alert(
                        String(localized: "common.something_went_wrong"),
                        isPresented: pickErrorPresented,
                        presenting: pickError
                    ) { _ in
                        Button(String(localized: "common.ok")) { pickError = nil }
                    } message: { message in
                        Text(message)
                    }
            }
            .interactiveDismissDisabled(isInProgress(observer))
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: ImportFlowObserver) -> some View {
        switch observer.phase {
        case .idle:
            ImportIntroContent { showingFileImporter = true }
        case .uploading(let filename):
            ImportProgressContent(
                title: String(localized: "import.uploading_title"),
                subtitle: String(localized: "import.uploading_hero_subtitle"),
                progress: nil,
                centerPrimary: nil,
                centerSecondary: nil,
                filename: filename,
                stats: []
            )
        case .analyzing(let model):
            ImportProgressContent(
                title: String(localized: "import.analyzing_hero_title"),
                subtitle: String(localized: "import.analyzing_hero_subtitle"),
                progress: model.fraction,
                centerPrimary: model.percent.map { "\($0)%" },
                centerSecondary: model.counterLabel,
                filename: nil,
                stats: analyzingStats(model)
            )
        case .review(let review):
            ImportReviewContent(
                review: review,
                assigningUser: $assigningUser,
                onAccept: { observer.assignUser(absUserId: $0.absUserId, listenUpUserId: $1) },
                onAssign: { assigningUser = $0 },
                onSkip: { observer.skipUser(absUserId: $0.absUserId) },
                onOpenBookSearch: { observer.openBookSearch(absItemId: $0) },
                onCloseBookSearch: { observer.closeBookSearch() },
                onBookSearchQueryChange: { observer.updateBookSearchQuery($0) },
                onSelectBook: { absItemId, bookId in observer.selectBook(absItemId: absItemId, bookId: bookId) },
                onSkipBook: { observer.skipBook(absItemId: $0) },
                onApply: { observer.confirmAndApply() }
            )
        case .applying(let model):
            ImportProgressContent(
                title: String(localized: "import.applying_hero_title"),
                subtitle: String(localized: "import.applying_hero_subtitle"),
                progress: model.fraction,
                centerPrimary: model.percent.map { "\($0)%" },
                centerSecondary: model.counterLabel,
                filename: nil,
                stats: applyingStats(model),
                footnote: String(localized: "import.keep_open_note")
            )
        case .done(let done):
            ImportCompleteContent(done: done) {
                onCompleted()
                dismiss()
            }
        case .error(let message):
            ImportErrorContent(message: message) {
                observer.reset()
            } onCancel: {
                dismiss()
            }
        }
    }

    private func analyzingStats(_ model: ImportProgressModel) -> [ImportStat] {
        [
            ImportStat(systemImage: "person", label: String(localized: "import.users_matched"), value: "\(model.usersMatched)"),
            ImportStat(systemImage: "books.vertical", label: String(localized: "import.books_matched_label"), value: "\(model.booksMatched)")
        ]
    }

    private func applyingStats(_ model: ImportProgressModel) -> [ImportStat] {
        [
            ImportStat(systemImage: "waveform", label: String(localized: "import.sessions_written_stat"), value: "\(model.sessionsWritten)")
        ]
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private func toolbar(_ observer: ImportFlowObserver) -> some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            if !isTerminal(observer) {
                Button(String(localized: "common.cancel")) { dismiss() }
            }
        }
    }

    private func navigationTitle(for observer: ImportFlowObserver) -> String {
        switch observer.phase {
        case .review: return String(localized: "import.review_users_section")
        default: return String(localized: "import.flow_title")
        }
    }

    // MARK: - File pick handling

    private func handlePickResult(_ result: Result<URL, Error>, observer: ImportFlowObserver) {
        switch result {
        case .success(let url):
            Task {
                do throws(ImportFilePickError) {
                    // Read the (multi-MB) backup OFF the main thread; doing it synchronously in the
                    // .fileImporter callback froze the UI on large backups. Only the `Sendable`
                    // `Data` crosses back — the non-`Sendable` Kotlin `FileSource` is built here.
                    let data = try await ImportFileSourceBridge.readData(from: url)
                    let fileSource = ImportFileSourceBridge.makeFileSource(
                        data: data,
                        filename: url.lastPathComponent
                    )
                    observer.start(fileSource: fileSource)
                } catch {
                    pickError = error.message
                }
            }
        case .failure:
            // User cancelled the picker, or it failed — stay on the intro silently.
            break
        }
    }

    // MARK: - Derived flags

    private func isTerminal(_ observer: ImportFlowObserver) -> Bool { observer.phase.isTerminal }

    /// In a server-side phase the sheet shouldn't be swipe-dismissed out from under the user.
    private func isInProgress(_ observer: ImportFlowObserver) -> Bool {
        switch observer.phase {
        case .uploading, .analyzing, .applying: return true
        default: return false
        }
    }

    private var pickErrorPresented: Binding<Bool> {
        Binding(get: { pickError != nil }, set: { if !$0 { pickError = nil } })
    }
}

// MARK: - Preview

#Preview {
    ImportWizardView()
}
