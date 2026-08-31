import SwiftUI
import Shared

/// Observes `BulkEditViewModel`, flattening `BulkEditUiState` into `@Observable` properties and
/// forwarding the form's edits back as setter calls.
///
/// Everything the views read is a native Swift value — the preview rows in particular, because a
/// Swift-Export-bridged Kotlin object re-bridges every property read on every SwiftUI diff (rule 8).
/// The mapping happens once, here, in `apply`.
///
/// **Errors are owned by this screen.** `BookSelectionObserver` claims bulk-action failures "surface
/// on the global `ErrorBus`" — no Swift file consumes that bus, so today's bulk shelf/collection
/// failures are silent on iOS. This observer does not repeat that: a `Failed` event becomes `error`,
/// and `BulkEditView` renders it as an alert. The shared ViewModel emits to both the bus and the
/// event channel precisely so a client that reads no bus is still told.
@Observable
@MainActor
final class BulkEditObserver {
    // MARK: - Flattened state

    private(set) var isLoading = true

    /// How many of the selected books actually loaded — the books Apply will touch.
    private(set) var bookCount = 0
    /// How many the user selected. Larger than `bookCount` when one could not be read.
    private(set) var requestedCount = 0

    private(set) var publisher = ""
    private(set) var year = ""
    private(set) var language = ""

    /// Hints for the untouched fields: the value the whole selection agrees on, or "Multiple values".
    private(set) var publisherPlaceholder = ""
    private(set) var yearPlaceholder = ""
    private(set) var languagePlaceholder = ""

    /// How many books would change at least one field — the number on the Apply button.
    private(set) var changedBookCount = 0
    private(set) var canApply = false
    private(set) var isApplying = false
    private(set) var preview: [BulkEditPreviewLine] = []

    private(set) var error: String?
    /// Flips once the apply finishes, which dismisses the sheet.
    private(set) var didFinish = false
    /// How many books the finished apply actually changed.
    private(set) var appliedCount = 0

    // MARK: - Dependencies

    private let viewModel: BulkEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: BulkEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
    }

    // Isolated deinit (SE-0371): there is no ViewModelStore on iOS to call `onCleared`, so this
    // observer closes the VM itself — otherwise the selection load keeps its coroutine scope alive
    // after the sheet is gone.
    isolated deinit {
        bridge.cancelAll()
        viewModel.close()
    }

    // MARK: - Field intents

    /// The publisher field changed. Blank removes the instruction rather than writing an empty value.
    func setPublisher(_ value: String) { viewModel.setPublisher(publisher: value) }

    /// The year field changed. Digits only, max 4 — the shared setter refuses a year outside
    /// `BookUpdate.MIN_YEAR...MAX_YEAR` rather than throwing, so filtering here keeps the field from
    /// showing text the ViewModel never accepted.
    func setYear(_ value: String) {
        let digits = String(value.filter(\.isNumber).prefix(4))
        viewModel.setYear(year: Int32(digits))
    }

    /// The language field changed. Blank removes the instruction.
    func setLanguage(_ value: String) { viewModel.setLanguage(language: value) }

    /// Apply every instruction to every loaded book.
    func apply() { viewModel.apply() }

    // MARK: - State mapping

    private func apply(_ state: BulkEditUiState) {
        switch onEnum(of: state) {
        case .loading:
            isLoading = true
        case .editing(let editing):
            isLoading = false
            bookCount = Int(editing.bookCount)
            requestedCount = Int(editing.requestedCount)
            publisher = editing.publisherInput
            year = editing.yearInput
            language = editing.languageInput
            publisherPlaceholder = BulkEditFormatting.placeholder(shared: editing.sharedPublisher)
            yearPlaceholder = BulkEditFormatting.placeholder(
                shared: editing.sharedPublishYear.map { String(Int($0)) }
            )
            languagePlaceholder = BulkEditFormatting.placeholder(shared: editing.sharedLanguage)
            changedBookCount = Int(editing.changedBookCount)
            canApply = editing.canApply
            isApplying = editing.isApplying
            preview = BulkEditMapping.previewLines(Array(editing.preview), bookCount: Int(editing.bookCount))
        case .unknown:
            // Swift cannot switch a Kotlin sealed interface exhaustively, so this branch is real
            // rather than unreachable: a state this build does not know about leaves the form as it
            // was instead of blanking it, and says so in the log.
            Log.error("Unexpected BulkEditUiState case")
        }
    }

    private func applyEvent(_ event: BulkEditEvent) {
        switch onEnum(of: event) {
        case .applied(let applied):
            appliedCount = Int(applied.changedCount)
            didFinish = true
        case .failed(let failed):
            appliedCount = Int(failed.appliedCount)
            error = BulkEditFormatting.failureMessage(
                reason: failed.error.message,
                appliedCount: Int(failed.appliedCount)
            )
        case .unknown:
            Log.error("Unexpected BulkEditEvent case")
        }
    }

    /// Dismiss the failure alert. The books already committed stand — there is nothing to undo.
    func dismissError() { error = nil }
}
