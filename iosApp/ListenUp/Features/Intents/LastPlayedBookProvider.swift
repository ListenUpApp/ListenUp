import ListenUpActivityKit
@preconcurrency import Shared

/// The app's `LastPlayedBookProviding` implementation — reads
/// `HomeRepository.getContinueListening(1)`, the same offline-first "Continue
/// Listening" source the Home screen renders. The top item is, by definition,
/// "resume my book"; its `bookId` feeds `ResumePlaybackIntent`.
/// Registered with `AppDependencyManager` at launch (see `ListenUpApp`).
@MainActor
struct LastPlayedBookProvider: LastPlayedBookProviding {

    func mostRecentBookId() async -> String? {
        // `getContinueListening` is a suspend `AppResult<List<ContinueListeningBook>>`;
        // Swift Export exposes it as `async throws` with the generic erased to `any AppResult`.
        // A thrown error (infra fault) or a typed `.failure` both mean "nothing to resume" —
        // never stranded: the intent surfaces a spoken "nothing to resume" message.
        guard let result = try? await Dependencies.shared.homeRepository.getContinueListening(limit: 1) else {
            return nil
        }
        switch appResultCase(result) {
        case .success(let success):
            let books = success.data as? [ContinueListeningBook]
            return books?.first?.bookId
        case .failure(let failure):
            Log.error("getContinueListening failed while resolving resume: \(failure.error.message)")
            return nil
        case .unknown:
            Log.error("getContinueListening returned an unexpected AppResult case")
            return nil
        }
    }
}
