import Observation
@preconcurrency import Shared

/// One hero's cover colours: neutral (`colors == nil`) until the shared `CoverThemeRepository` answers. A cover this
/// process has already read paints at once (`fadesIn == false`); one decoded now crossfades in (`fadesIn == true`).
/// Reusable by any hero: Book Detail, Home's slot, and (Player PR 3) Now Playing.
@Observable
@MainActor
final class CoverThemeObserver {
    private(set) var colors: CoverColors?
    private(set) var fadesIn = false

    @ObservationIgnored private let repository: any CoverThemeRepository
    @ObservationIgnored private let bridge = FlowBridge()
    @ObservationIgnored private var loaded: String?

    init(repository: any CoverThemeRepository = Dependencies.shared.coverThemeRepository) {
        self.repository = repository
    }

    /// Loads `bookId`'s cover at `coverHash`; a repeat call for the same cover does nothing.
    func load(bookId: String, coverHash: String?) {
        let key = "\(bookId)#\(coverHash ?? "")"
        guard key != loaded else { return }
        loaded = key
        bridge.cancelAll()
        let id = BookId(value: bookId)
        let style = CoverThemeStyle.listenUp
        if let known = repository.cached(bookId: id, coverHash: coverHash, style: style) {
            fadesIn = false
            colors = CoverColors(known)
            return
        }
        colors = nil
        fadesIn = true
        bridge.bind(repository.observe(bookId: id, coverHash: coverHash, style: style)) { [weak self] themes in
            self?.colors = themes.map(CoverColors.init)
        }
    }
}
