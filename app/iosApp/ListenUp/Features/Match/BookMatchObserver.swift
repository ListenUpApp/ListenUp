import Foundation
import Observation
import Shared

/// Observes one Match details session — `BookMatchViewModel` — for every layout: the iPhone push and
/// the iPad split view read the same Find and Review values and send the same actions.
///
/// Thin over `FlowBridge`: each shared state is mapped once by `BookMatchMapping` into native values,
/// and the bridged candidate keys stay here, off every diff path, for the actions that need them.
/// `appliedToken` counts `BookMatchEvent.Applied`, so the presenting layout returns to Book Detail;
/// `reloadedToken` counts `ReviewReloaded`, which Review says out loud. Errors reach the person through
/// the shared bus as well as inline, so this observer keeps no error state of its own.
@Observable
@MainActor
final class BookMatchObserver {
    private(set) var find: MatchFind = .initial
    private(set) var review: MatchReviewPhase = .noneChosen
    /// Bumped on every Apply that committed.
    private(set) var appliedToken = 0
    /// Bumped when Apply found the book changed and Review reloaded.
    private(set) var reloadedToken = 0
    /// Whether Review shows "This book changed while you were reviewing."
    private(set) var showsReviewReloaded = false

    private let viewModel: BookMatchViewModel
    private let bridge = FlowBridge()
    /// Candidate id → the bridged key `pick` needs. Never handed to a view.
    private var candidateKeys: [String: BookCandidateKey] = [:]
    /// The last Review state, re-mapped when the viewer is known ("Edited by you").
    private var lastReview: (any ReviewUiState)?
    private var viewerId: String?

    init(viewModel: BookMatchViewModel, userRepository: UserRepository = KoinHelper.shared.getUserRepository()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.findState) { [weak self] in self?.applyFind($0) }
        bridge.bind(viewModel.reviewState) { [weak self] in self?.applyReview($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
        bridge.bind(userRepository.observeCurrentUser()) { [weak self] user in
            guard let self else { return }
            viewerId = user?.idString
            if let lastReview { review = BookMatchMapping.review(from: lastReview, viewerId: viewerId) }
        }
    }

    // Isolated deinit (SE-0371): iOS has no ViewModelStore to call `onCleared`, so the observer closes the shared
    // ViewModel itself — otherwise an in-flight search, review or Apply keeps its scope alive after the screen.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }

    // MARK: - Find

    func search(_ query: String) { viewModel.search(query: query) }
    func searchByTitle() { viewModel.searchByTitle() }
    func retry() { viewModel.retry() }
    func chooseStore(_ store: MatchStoreChoice) { viewModel.chooseStoreForThisSearch(region: store.locale) }

    /// Runs a failure's way forward.
    func perform(_ action: MatchFailureAction) {
        switch action {
        case .retry, .retryCountdown: retry()
        case .tryStore(_, let store): chooseStore(store)
        case .searchByTitle: searchByTitle()
        }
    }

    /// Opens a candidate's Review. Re-opening the one already open does nothing, so a view that appears
    /// again never reloads it.
    func pick(_ candidateId: String) {
        guard review.candidateId != candidateId, let key = candidateKeys[candidateId] else { return }
        showsReviewReloaded = false
        viewModel.pick(key: key)
    }

    /// Loads a Review that failed again.
    func retryReview(_ candidateId: String) {
        guard let key = candidateKeys[candidateId] else { return }
        viewModel.pick(key: key)
    }

    /// Back to the intact results.
    func backToResults() {
        showsReviewReloaded = false
        viewModel.backToResults()
    }

    /// Two panes open the best Strong match straight away; one pane waits for a tap.
    func useTwoPane(_ enabled: Bool) { viewModel.useTwoPane(enabled: enabled) }

    // MARK: - Review

    func setTicked(_ field: BookField, _ ticked: Bool) { viewModel.setFieldTicked(field: field, ticked: ticked) }

    func chooseSource(_ field: BookField, _ selection: MatchSourceSelection) {
        viewModel.chooseSource(field: field, choice: Self.fieldChoice(selection))
    }

    func chooseCover(_ tileId: String) { viewModel.chooseCover(choice: Self.imageChoice(tileId)) }

    func removeLabel(_ kind: LabelKind, _ label: String) { viewModel.removeYourLabel(kind: kind, label: label) }
    func restoreLabel(_ kind: LabelKind, _ label: String) { viewModel.restoreYourLabel(kind: kind, label: label) }
    func toggleSuggestion(_ kind: LabelKind, _ label: String) { viewModel.toggleSuggestion(kind: kind, label: label) }
    func setChapterNamesIncluded(_ included: Bool) { viewModel.setChapterNamesIncluded(included: included) }
    func toggleChapter(_ ordinal: Int32) { viewModel.toggleChapter(ordinal: ordinal) }

    func apply() {
        showsReviewReloaded = false
        viewModel.apply()
    }

    func dismissReviewReloaded() { showsReviewReloaded = false }

    // MARK: - Choices to the shared types

    static func fieldChoice(_ selection: MatchSourceSelection) -> any FieldChoice {
        switch selection {
        case .option(let optionId): FieldChoiceOption(optionId: optionId)
        case .keepYours: FieldChoiceKeepCurrent.shared
        }
    }

    static func imageChoice(_ tileId: String) -> any ImageChoice {
        tileId == "keep" ? ImageChoiceKeepCurrent.shared : ImageChoiceCandidate(optionId: tileId)
    }

    /// Every candidate a state can pick, by id.
    static func candidateKeys(in state: any FindUiState) -> [String: BookCandidateKey] {
        let candidates: [CandidateUi] = switch state.sealedType() {
        case .searching(let searchingType): searchingType.value.previous?.all ?? []
        case .results(let resultsType): resultsType.value.all
        case .failed: []
        }
        return Dictionary(candidates.map { ($0.id, $0.key) }) { first, _ in first }
    }

    // MARK: - State

    private func applyFind(_ state: any FindUiState) {
        candidateKeys.merge(Self.candidateKeys(in: state)) { _, new in new }
        find = BookMatchMapping.find(from: state)
    }

    private func applyReview(_ state: any ReviewUiState) {
        lastReview = state
        review = BookMatchMapping.review(from: state, viewerId: viewerId)
    }

    private func applyEvent(_ event: any BookMatchEvent) {
        switch event.sealedType() {
        case .applied:
            appliedToken += 1
        case .reviewReloaded:
            showsReviewReloaded = true
            reloadedToken += 1
        }
    }
}

/// Observes a receipt — `MatchReceiptViewModel` — as one native phase, for Book Detail or the contributor
/// page. A person's name arrives with their page, so a new `subject` re-says the receipt already shown.
@Observable
@MainActor
final class MatchReceiptObserver {
    private(set) var phase: MatchReceiptPhase = .none
    var subject: MatchReceiptSubject {
        didSet { if let lastState { phase = BookMatchMapping.receipt(from: lastState, subject: subject) } }
    }

    private let viewModel: MatchReceiptViewModel
    private let bridge = FlowBridge()
    /// The last shared state, re-said when `subject` changes. Never handed to a view.
    private var lastState: (any MatchReceiptUiState)?

    init(viewModel: MatchReceiptViewModel, subject: MatchReceiptSubject = .book) {
        self.viewModel = viewModel
        self.subject = subject
        bridge.bind(viewModel.state) { [weak self] state in
            guard let self else { return }
            lastState = state
            phase = BookMatchMapping.receipt(from: state, subject: self.subject)
        }
    }

    // Isolated deinit (SE-0371): iOS has no ViewModelStore to call `onCleared`, so the observer closes the shared
    // ViewModel itself — otherwise an in-flight Undo keeps its scope alive after the page.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }

    func undo() { viewModel.undo() }
    func dismiss() { viewModel.dismiss() }
}
