import SwiftUI
import Shared

/// The Book Detail Hardcover section, native.
enum BookHardcoverPhase: Equatable {
    /// No section: not connected, or the server's answer isn't known.
    case hidden
    /// Never matched (decision 1): the Toggle alone, on; switching it off never asks first.
    case unmatched
    case needsMatch
    case linked(BookHardcoverLinkedModel)
    /// Kept off Hardcover (#1541). `isResuming` while syncing it again saves: the Toggle already reads on.
    case keptOff(isResuming: Bool)

    /// Whether Sync with Hardcover reads on.
    var isSyncOn: Bool {
        if case .keptOff(let isResuming) = self { return isResuming }
        return true
    }
}

/// A matched book: what it is matched to, whether the user chose it, and where it stands.
struct BookHardcoverLinkedModel: Equatable {
    let title: String
    /// "Andy Weir · 2021".
    let byline: String?
    let chosenByYou: Bool
    let status: BookHardcoverStatus
    /// What the confirmation says before keeping it off; nil when nothing visible would leave, so it switches off at once.
    var keepOffMessage: String?
}

/// Where a matched book stands with Hardcover, as one line with its glyph and tone.
struct BookHardcoverStatus: Equatable {
    /// How the line is coloured. Never colour alone: each tone has its own glyph.
    enum Tone: Equatable {
        /// Up to date, or matched just now.
        case settled
        /// Updating, or not sent yet.
        case quiet
        /// Stopped because it was removed on Hardcover.
        case caution
    }

    let text: String
    let systemImage: String
    let tone: Tone
}

/// Observes `BookHardcoverViewModel` and flattens its state for the Book Detail section.
@Observable
@MainActor
final class BookHardcoverObserver {
    private(set) var phase: BookHardcoverPhase = .hidden
    private let viewModel: BookHardcoverViewModel
    private let bridge = FlowBridge()

    init(viewModel: BookHardcoverViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.phase = Self.phase(from: $0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    /// Removes the book's match: it then needs one. Callers confirm first; a refusal reaches the
    /// shared error bus, which `GlobalErrorObserver` shows as an alert.
    func removeMatch() { viewModel.removeMatch() }

    /// Sync with Hardcover: off keeps the book off Hardcover, on syncs it again (#1541). Callers confirm first when
    /// the linked model has a `keepOffMessage`. A refusal reaches the shared error bus, shown as an alert.
    func setSynced(_ synced: Bool) { viewModel.setSynced(synced: synced) }

    // MARK: - Pure mappings (unit-tested)

    nonisolated static func phase(from state: BookHardcoverUiState) -> BookHardcoverPhase {
        switch state.sealedType() {
        case .hidden: return .hidden
        case .unmatched: return .unmatched
        case .needsMatch: return .needsMatch
        case .keptOff(let keptOffType): return .keptOff(isResuming: keptOffType.value.isResuming)
        case .linked(let linkedType):
            let linked = linkedType.value
            return .linked(
                BookHardcoverLinkedModel(
                    title: linked.match.title ?? String(localized: "hardcover.book_row_matched_unnamed"),
                    byline: HardcoverMatchObserver.byline(
                        authors: linked.match.authors,
                        year: linked.match.releaseYear
                    ),
                    chosenByYou: linked.match.chosenByYou,
                    status: linked.justMatched ? justMatched : status(for: linked.sync),
                    keepOffMessage: keepOffMessage(for: linked.keepOffRemoves)
                )
            )
        }
    }

    /// The first minute after this device made the match reads "Matched just now", in place of the sync.
    nonisolated static let justMatched = BookHardcoverStatus(
        text: String(localized: "hardcover.book_row_just_matched"),
        systemImage: "checkmark.circle.fill",
        tone: .settled
    )

    // Deliberately no `default`: a new state must fail to compile here rather than borrow another's words.
    nonisolated static func status(for sync: HardcoverBookSync) -> BookHardcoverStatus {
        switch sync {
        case .upToDate:
            BookHardcoverStatus(
                text: String(localized: "hardcover.book_sync_up_to_date"),
                systemImage: "checkmark.circle.fill",
                tone: .settled
            )
        case .waiting:
            BookHardcoverStatus(
                text: String(localized: "hardcover.book_sync_waiting"),
                systemImage: "arrow.triangle.2.circlepath",
                tone: .quiet
            )
        case .nothingSentYet:
            BookHardcoverStatus(
                text: String(localized: "hardcover.book_sync_nothing_yet"),
                systemImage: "circle.dashed",
                tone: .quiet
            )
        case .removedOnHardcover:
            BookHardcoverStatus(
                text: String(localized: "hardcover.book_sync_removed"),
                systemImage: "exclamationmark.triangle.fill",
                tone: .caution
            )
        }
    }

    /// The confirmation's message, naming exactly what leaves (#1541). Deliberately no `default`: a new kind must
    /// fail to compile here rather than borrow another's words.
    nonisolated static func keepOffMessage(for removes: KeepOffRemoves?) -> String? {
        guard let removes else { return nil }
        switch removes {
        case .reads: return String(localized: "hardcover.keep_off_confirm_body_reads")
        case .toRead: return String(localized: "hardcover.keep_off_confirm_body_to_read")
        case .readsAndToRead: return String(localized: "hardcover.keep_off_confirm_body")
        }
    }
}
