import Foundation

/// What the player is doing right now — the single source of truth for runtime
/// playback state, owned by `PlayerCoordinator`. Sealed by construction: illegal
/// state combinations cannot be represented.
enum PlayerPhase: Equatable, Sendable {
    /// No book loaded.
    case idle
    /// A book is being prepared (KMP `PlaybackPreparer` in flight).
    case preparing(PreparingState)
    /// A book is loaded and audio is advancing.
    case playing(PlayingState)
    /// A book is loaded and paused.
    case paused(PlayingState)
    /// A book is loaded; audio is stalled while the engine buffers.
    case buffering(PlayingState)
    /// Playback could not start or could not continue.
    case error(ErrorState)

    /// The loaded-book payload — present for `playing`, `paused`, and `buffering`.
    var playingState: PlayingState? {
        switch self {
        case .playing(let s), .paused(let s), .buffering(let s): return s
        case .idle, .preparing, .error: return nil
        }
    }

    /// Whether audio is actively advancing.
    var isPlaying: Bool {
        if case .playing = self { return true }
        return false
    }

    /// The book this phase concerns, if any.
    var bookId: String? {
        switch self {
        case .idle: return nil
        case .preparing(let s): return s.bookId
        case .playing(let s), .paused(let s), .buffering(let s): return s.bookId
        case .error(let s): return s.bookId
        }
    }
}

/// Context for `PlayerPhase.preparing`.
struct PreparingState: Equatable, Sendable {
    let bookId: String
}

/// Context shared by `playing`, `paused`, and `buffering` — the "a book is loaded" payload.
struct PlayingState: Equatable, Sendable {
    let bookId: String
    /// Total book duration in milliseconds.
    let durationMs: Int64
}

/// Context for `PlayerPhase.error`.
struct ErrorState: Equatable, Sendable {
    /// User-facing message.
    let message: String
    /// The book the error relates to, if any — supports a retry affordance.
    let bookId: String?
}
