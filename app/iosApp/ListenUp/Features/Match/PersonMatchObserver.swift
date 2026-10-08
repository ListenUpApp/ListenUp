import Foundation
import Observation
import Shared

/// Observes one person Match details session — `PersonMatchViewModel` — for every layout: the iPhone push
/// and the iPad split view read the same Find and Review values and send the same actions.
///
/// Thin over `FlowBridge`: each shared state is mapped once by `PersonMatchMapping` into native values, and
/// the bridged candidate keys stay here, off every diff path, for the actions that need them.
/// `appliedToken` counts `PersonMatchEvent.Applied`, so the presenting layout returns to the contributor
/// page; `reloadedToken` counts `ReviewReloaded`, which Review says out loud. Errors reach the person through
/// the shared bus as well as inline, so this observer keeps no error state of its own.
@Observable
@MainActor
final class PersonMatchObserver {
    private(set) var find: PersonFind = .initial
    private(set) var review: PersonReviewPhase = .noneChosen
    /// Bumped on every Apply that committed.
    private(set) var appliedToken = 0
    /// Bumped when Apply found the person changed and Review reloaded.
    private(set) var reloadedToken = 0
    /// Whether Review shows "This person changed while you were reviewing."
    private(set) var showsReviewReloaded = false

    private let viewModel: PersonMatchViewModel
    private let bridge = FlowBridge()
    /// Candidate id → the bridged key `pick` needs. Never handed to a view.
    private var candidateKeys: [String: PersonCandidateKey] = [:]
    /// The last Review state, re-mapped when the viewer is known ("Edited by you").
    private var lastReview: (any PersonReviewUiState)?
    private var viewerId: String?

    init(viewModel: PersonMatchViewModel, userRepository: UserRepository = KoinHelper.shared.getUserRepository()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.findState) { [weak self] in self?.applyFind($0) }
        bridge.bind(viewModel.reviewState) { [weak self] in self?.applyReview($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
        bridge.bind(userRepository.observeCurrentUser()) { [weak self] user in
            guard let self else { return }
            viewerId = user?.idString
            if let lastReview { review = PersonMatchMapping.review(from: lastReview, viewerId: viewerId) }
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Find

    func search(_ query: String) { viewModel.search(query: query) }
    func retry() { viewModel.retry() }

    /// Runs a failure's way forward. A person search has only Retry.
    func perform(_ action: MatchFailureAction) {
        switch action {
        case .retry, .retryCountdown: retry()
        case .tryStore, .searchByTitle: break
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

    func setPhotoTicked(_ ticked: Bool) { viewModel.setPhotoTicked(ticked: ticked) }

    /// A photo source segment, or Keep current.
    func choosePhoto(_ selection: MatchSourceSelection) { viewModel.choosePhoto(choice: Self.imageChoice(selection)) }

    func setBiographyTicked(_ ticked: Bool) { viewModel.setBiographyTicked(ticked: ticked) }

    func chooseBiographySource(_ selection: MatchSourceSelection) {
        viewModel.chooseBiographySource(choice: BookMatchObserver.fieldChoice(selection))
    }

    func apply() {
        showsReviewReloaded = false
        viewModel.apply()
    }

    // MARK: - Choices to the shared types

    static func imageChoice(_ selection: MatchSourceSelection) -> any ImageChoice {
        switch selection {
        case .option(let optionId): ImageChoiceCandidate(optionId: optionId)
        case .keepYours: ImageChoiceKeepCurrent.shared
        }
    }

    /// Every candidate a state can pick, by id.
    static func candidateKeys(in state: any PersonFindUiState) -> [String: PersonCandidateKey] {
        let candidates: [PersonCandidateUi] = switch state.sealedType() {
        case .searching(let searchingType): searchingType.value.previous?.all ?? []
        case .results(let resultsType): resultsType.value.all
        case .noProfiles, .failed: []
        }
        return Dictionary(candidates.map { ($0.id, $0.key) }) { first, _ in first }
    }

    // MARK: - State

    private func applyFind(_ state: any PersonFindUiState) {
        candidateKeys.merge(Self.candidateKeys(in: state)) { _, new in new }
        find = PersonMatchMapping.find(from: state)
    }

    private func applyReview(_ state: any PersonReviewUiState) {
        lastReview = state
        review = PersonMatchMapping.review(from: state, viewerId: viewerId)
    }

    private func applyEvent(_ event: any PersonMatchEvent) {
        switch event.sealedType() {
        case .applied:
            appliedToken += 1
        case .reviewReloaded:
            showsReviewReloaded = true
            reloadedToken += 1
        }
    }
}
