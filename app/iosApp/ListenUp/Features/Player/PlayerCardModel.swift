import Foundation
import ListenupContract
@preconcurrency import Shared

/// The shared state card (`PlayerCards`) as native values (rule 8): the problem's words, or the finished book, and its
/// two actions. Built once per `uiState` emission, so the card on Now Playing offers the same ways out as Android's and
/// the web's.
struct PlayerCardModel: Equatable {
    /// How the card shows its kind: a red glyph and title, the held amber, or a plain information glyph.
    enum Tone: Equatable {
        case error, held, neutral

        init(_ tone: any PlayerCardTone) {
            self = switch tone.sealedType() {
            case .error: .error
            case .held: .held
            case .neutral: .neutral
            }
        }
    }

    enum Kind: Equatable {
        case problem(title: String, cause: String?, placeSaved: Bool, reference: String?, tone: Tone)
        case finished(FinishedModel)
        /// The sleep timer is fading the audio out: the gold card, with Keep Listening alone (§5.6).
        case sleepFading
    }

    /// A card button. Each one is a shared `PlayerCardAction` that `PlayerViewModel.onCardAction` carries out.
    enum Action: CaseIterable, Equatable {
        case retry, dismiss, closeBook, signIn, openInbox, resume, tapToPlay, listenAgain, playNextBook, keepListening

        init(_ action: any PlayerCardAction) {
            self = switch action.sealedType() {
            case .retry: .retry
            case .dismiss: .dismiss
            case .closeBook: .closeBook
            case .signIn: .signIn
            case .openInbox: .openInbox
            case .resume: .resume
            case .tapToPlay: .tapToPlay
            case .listenAgain: .listenAgain
            case .playNextBook: .playNextBook
            case .keepListening: .keepListening
            }
        }

        /// The shared value `PlayerViewModel.onCardAction` takes.
        var kotlin: any PlayerCardAction {
            switch self {
            case .retry: PlayerCardActionRetry.shared
            case .dismiss: PlayerCardActionDismiss.shared
            case .closeBook: PlayerCardActionCloseBook.shared
            case .signIn: PlayerCardActionSignIn.shared
            case .openInbox: PlayerCardActionOpenInbox.shared
            case .resume: PlayerCardActionResume.shared
            case .tapToPlay: PlayerCardActionTapToPlay.shared
            case .listenAgain: PlayerCardActionListenAgain.shared
            case .playNextBook: PlayerCardActionPlayNextBook.shared
            case .keepListening: PlayerCardActionKeepListening.shared
            }
        }

        /// The button's words, in the HIG's title case.
        var title: String {
            switch self {
            case .retry: String(localized: "player.retry")
            case .dismiss: String(localized: "player.np_dismiss")
            case .closeBook: String(localized: "player.np_close_book_ios")
            case .signIn: String(localized: "player.np_sign_in_ios")
            case .openInbox: String(localized: "player.np_open_inbox_ios")
            case .resume: String(localized: "player.mini_resume")
            case .tapToPlay: String(localized: "player.play")
            case .listenAgain: String(localized: "player.mini_listen_again_ios")
            case .playNextBook: String(localized: "home.play_next_book_ios")
            case .keepListening: String(localized: "player.mini_keep_listening_ios")
            }
        }
    }

    let kind: Kind
    let primary: Action
    let secondary: Action?

    init(_ card: any PlayerCard) {
        switch card.sealedType() {
        case .problem(let wrapped):
            let problem = wrapped.value
            let error = problem.error
            kind = .problem(
                title: PlayerSnapshot.title(for: error),
                cause: PlayerSnapshot.cause(for: error),
                placeSaved: problem.placeSaved,
                reference: error.correlationId.map { String(format: String(localized: "player.reference"), $0) },
                tone: Tone(problem.tone)
            )
            primary = Action(problem.primaryAction)
            secondary = problem.secondaryAction.map(Action.init)
        case .finished(let wrapped):
            let finished = wrapped.value
            kind = .finished(FinishedModel(finished.info))
            primary = Action(finished.primaryAction)
            secondary = finished.secondaryAction.map(Action.init)
        case .sleepFading:
            kind = .sleepFading
            primary = .keepListening
            secondary = nil
        }
    }
}

/// The finished card's facts (§3.7), native.
struct FinishedModel: Equatable {
    let finishedAt: Date?
    let startedAt: Date?
    let durationMs: Int64
    /// 2...10, or nil when not rated yet.
    let yourHalfStars: Int?
    let next: NextBookItem?

    init(_ info: FinishedInfo) {
        finishedAt = info.finishedAtMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        startedAt = info.startedAtMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        durationMs = info.durationMs
        yourHalfStars = info.yourHalfStars.map { Int($0) }
        next = info.nextBook.map(NextBookItem.init(from:))
    }
}
