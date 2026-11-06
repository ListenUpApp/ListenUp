import CarPlay
import UIKit

/// Hosts the CarPlay scene.
///
/// CarPlay's now-playing screen is not something this app draws: `CPNowPlayingTemplate.shared`
/// renders straight from `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`, both of which
/// `SystemIntegration` already keeps current and chapter-scoped. So the head unit shows the
/// current chapter, its real duration, and working transport controls without a line of
/// CarPlay-specific playback code. If something reads wrong in the car, fix
/// `SystemIntegration.dictionary(from:)` — the lock screen shares it, and a CarPlay-only patch
/// would just make the two surfaces disagree.
///
/// The browse tree mirrors the phone app (`MainTabView`), not Android Auto: the
/// continue-listening shelf — overwhelmingly what a driver wants — and the Library, in the
/// phone's own sort order. Keyboard search is not possible here (`CPSearchTemplate` is
/// navigation-entitlement-only); in-car search is Siri, via the existing App Intents. Discover
/// is deliberately absent — it is mostly a leaderboard and a friend activity feed, which is the
/// wrong thing to put in front of someone driving.
@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    /// The template handed to `setRootTemplate` on connect.
    ///
    /// `CPNowPlayingTemplate` must never appear in here: CarPlay's `validateTemplates:` rejects
    /// it as a tab bar member with an `NSInvalidArgumentException` the moment the head unit
    /// connects (allowed classes are the browse templates — list, grid, information,
    /// point-of-interest). Now-playing is reached by *pushing* `CPNowPlayingTemplate.shared`,
    /// as the row handler does, and the system adds its own Now Playing affordance while audio
    /// plays.
    static func rootTemplate(home: CPListTemplate, library: CPListTemplate) -> CPTemplate {
        CPTabBarTemplate(templates: [home, library])
    }

    /// Valid only between connect and disconnect, so it is held weakly and every use is optional.
    private weak var interfaceController: CPInterfaceController?

    /// Built on connect and released on disconnect: their bridges cancel the underlying streams
    /// (and clear the Library's Kotlin ViewModel), which would otherwise outlive the car session (#1192).
    private var home: CarPlayHomeWrapper?
    private var library: CarPlayLibraryWrapper?

    private let homeTemplate: CPListTemplate = {
        let template = CPListTemplate(
            title: String(localized: "home.continue_listening"),
            sections: []
        )
        template.tabImage = UIImage(systemName: "play.circle")
        return template
    }()

    let libraryTemplate: CPListTemplate = {
        let template = CPListTemplate(
            title: String(localized: "common.library"),
            sections: []
        )
        template.tabImage = UIImage(systemName: "books.vertical")
        return template
    }()

    /// Downsampled row artwork, keyed by cover path. At most one entry per browse row, and the
    /// whole cache lives only for the car session — released with the delegate's other per-session
    /// state on disconnect.
    private var rowArtwork: [String: UIImage] = [:]

    /// The three observations that keep the car current, each cancelled on disconnect. Held as tasks (not re-armed by
    /// hand) because `Observations` re-arms itself and ends only when its task does.
    private var homeTask: Task<Void, Never>?
    private var libraryTask: Task<Void, Never>?
    /// Fills the Library's covers behind its text rows; replaced on every render and cancelled on disconnect.
    private var libraryArtworkTask: Task<Void, Never>?

    /// Decodes one cover off the main actor. A seam so a test can hold decodes back and see the list appear first.
    var decodeArtwork: @Sendable (_ path: String, _ maxPixels: Int) async -> UIImage? = { path, maxPixels in
        await CarPlayArtwork.decode(path: path, maxPixels: maxPixels)
    }
    private var chapterButtonsTask: Task<Void, Never>?

    /// Pushes the now-playing buttons only when they change.
    private lazy var buttonsPresenter = CarPlayButtonsPresenter { [weak self] in
        self?.updateNowPlayingButtons($0)
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController

        let home = CarPlayHomeWrapper()
        self.home = home
        observeHome(home)

        let library = CarPlayLibraryWrapper()
        self.library = library
        observeLibrary(library)

        configureNowPlaying()

        let root = Self.rootTemplate(home: homeTemplate, library: libraryTemplate)
        interfaceController.setRootTemplate(root, animated: false) { _, error in
            if let error {
                Log.error("CarPlay root template failed", error: error)
            } else {
                Log.info("CarPlay connected")
            }
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
        homeTask?.cancel()
        libraryTask?.cancel()
        chapterButtonsTask?.cancel()
        libraryArtworkTask?.cancel()
        libraryArtworkTask = nil
        homeTask = nil
        libraryTask = nil
        chapterButtonsTask = nil
        home = nil
        library = nil
        rowArtwork.removeAll()
        resetNowPlaying()
        Log.info("CarPlay disconnected")
    }

    /// Re-render the Home list whenever the books in progress change.
    ///
    /// `Observations` yields the current content and again after every change to what the closure read, so the car
    /// never shows whatever the library looked like at the moment the driver plugged in.
    private func observeHome(_ home: CarPlayHomeWrapper) {
        homeTask = Task { [weak self] in
            for await content in Observations({ Self.homeContent(of: home) }) {
                if Task.isCancelled { break }
                self?.render(content)
            }
        }
    }

    /// What the Home list shows, read in one place so the observation tracks exactly these two properties.
    private enum HomeContent: Equatable, Sendable {
        case failure(String)
        case rows([CarPlayRow])
    }

    private static func homeContent(of home: CarPlayHomeWrapper) -> HomeContent {
        if let message = home.failureMessage { return .failure(message) }
        return .rows(CarPlayRows.continueListening(from: home.items))
    }

    private func render(_ content: HomeContent) {
        let items: [CPListItem]
        switch content {
        case .failure(let message):
            // Honest over silent: a driver seeing an empty list cannot tell "nothing to resume"
            // from "we failed to load". Untappable, so it cannot lead anywhere.
            let row = CPListItem(text: message, detailText: nil)
            row.handler = { _, completion in completion() }
            items = [row]
        case .rows(let rows):
            items = rows.map(listItem(for:))
        }
        homeTemplate.updateSections([CPListSection(items: items)])
    }

    /// Re-render the Library list whenever the sorted book list changes — the same `Observations` shape as
    /// `observeHome`.
    private func observeLibrary(_ library: CarPlayLibraryWrapper) {
        libraryTask = Task { [weak self] in
            for await books in Observations({ library.books }) {
                if Task.isCancelled { break }
                self?.renderLibrary(books)
            }
        }
    }

    /// Shows the Library at once with whatever covers are already decoded, then fills the rest in off the main actor:
    /// the collection is as long as the head unit allows, and decoding it all here held the main actor for the whole
    /// of it.
    func renderLibrary(_ books: [BookRow]) {
        let rows = CarPlayRows.library(from: books, limit: CPListTemplate.maximumItemCount)
        let items = rows.map { listItem(for: $0, image: cachedArtwork(for: $0)) }
        libraryTemplate.updateSections([CPListSection(items: items)])
        fillArtwork(for: zip(rows, items).compactMap { row, item in
            guard let path = row.coverPath, rowArtwork[path] == nil else { return nil }
            return (path, item)
        })
    }

    /// Decodes each missing cover in turn and sets it on its row. A re-render or a disconnect cancels what is still
    /// pending, so a stale list never receives a late image.
    private func fillArtwork(for pending: [(path: String, item: CPListItem)]) {
        libraryArtworkTask?.cancel()
        guard !pending.isEmpty else { return }
        let maxPixels = coverPixelSize
        let decode = decodeArtwork
        libraryArtworkTask = Task { [weak self] in
            for (path, item) in pending {
                let image = await decode(path, maxPixels)
                guard !Task.isCancelled, let self else { return }
                guard let image else { continue }
                rowArtwork[path] = image
                item.setImage(image)
            }
        }
    }

    private func listItem(for row: CarPlayRow) -> CPListItem {
        listItem(for: row, image: rowImage(for: row))
    }

    private func listItem(for row: CarPlayRow, image: UIImage?) -> CPListItem {
        let item = CPListItem(text: row.title, detailText: row.detailText, image: image)
        item.handler = { [weak self] _, completion in
            Dependencies.shared.playerObserver.play(bookId: row.id)
            // Push now-playing so the driver lands on transport controls rather than staying in
            // a list they have to navigate out of.
            self?.interfaceController?.pushTemplate(CPNowPlayingTemplate.shared, animated: true) { _, error in
                if let error { Log.error("CarPlay now-playing push failed", error: error) }
                completion()
            }
        }
        return item
    }

    // MARK: - Now playing customization

    /// Configure the shared now-playing template for this car session: a playback-rate button,
    /// chapter prev/next buttons, and the Up Next button as a "Chapters" list.
    ///
    /// The rate button is the system's `CPNowPlayingPlaybackRateButton`: its tap steps through
    /// `PlaybackRates`, and CarPlay draws its current value from the `changePlaybackRateCommand`
    /// that `SystemIntegration` registers for the lock screen too (rule 13).
    ///
    /// The chapter buttons go through the shared player, so they follow the rule every surface does (§3.3):
    /// previous restarts the chapter after 3 s and chapter 1 restarts; next is disabled at the last chapter. Next and
    /// previous track are also registered with the remote command center (`SystemIntegration`) for AirPods and
    /// headsets, which this template may draw as well. These custom buttons stay only while it does not: if the
    /// system transport row draws its own track buttons, the custom pair is a duplicate, and removing it is a
    /// deletion with no other change. The 10/30 interval skips already render in the system transport row, so they
    /// are never custom.
    private func configureNowPlaying() {
        let nowPlaying = CPNowPlayingTemplate.shared
        observeChapterButtons()
        nowPlaying.isUpNextButtonEnabled = true
        nowPlaying.upNextTitle = String(localized: "player.chapters")
        nowPlaying.add(self)
    }

    /// Re-draw the buttons whenever previous or next becomes possible or impossible, and only then: the player's other
    /// changes re-run the observation, and the presenter drops an unchanged set. Ends with the car's session.
    private func observeChapterButtons() {
        chapterButtonsTask = Task { [weak self] in
            let player = Dependencies.shared.playerObserver
            let buttons = Observations({
                CarPlayNowPlayingButtons(
                    hasPreviousChapter: player.canSkipToPreviousChapter, hasNextChapter: player.canSkipToNextChapter)
            })
            for await value in buttons {
                if Task.isCancelled { break }
                self?.buttonsPresenter.show(value)
            }
        }
    }

    private func updateNowPlayingButtons(_ buttons: CarPlayNowPlayingButtons) {
        updateNowPlayingButtons(
            hasPreviousChapter: buttons.hasPreviousChapter, hasNextChapter: buttons.hasNextChapter)
    }

    private func updateNowPlayingButtons(hasPreviousChapter: Bool, hasNextChapter: Bool) {
        let chapterButtons = CarPlayChapterButton.all.map { spec in
            let image = buttonImage(spec.symbolName, accessibilityLabel: spec.accessibilityLabel)
            let button = CPNowPlayingImageButton(image: image) { _ in
                Task { @MainActor in
                    let player = Dependencies.shared.playerObserver
                    switch spec.action {
                    case .previous: player.previousChapter()
                    case .next: player.nextChapter()
                    }
                }
            }
            button.isEnabled = spec.isEnabled(hasPreviousChapter: hasPreviousChapter, hasNextChapter: hasNextChapter)
            return button
        }

        let rateButton = CPNowPlayingPlaybackRateButton { _ in
            Task { @MainActor in
                let player = Dependencies.shared.playerObserver
                player.setSpeed(PlaybackRates.next(after: player.playbackSpeed))
            }
        }

        let buttons: [CPNowPlayingButton] = [rateButton] + chapterButtons
        CPNowPlayingTemplate.shared.updateNowPlayingButtons(buttons)
    }

    /// Return the shared template to its defaults. It is a process-wide singleton, so leaving our
    /// buttons and observer behind would leak them into whatever configures it next.
    private func resetNowPlaying() {
        let nowPlaying = CPNowPlayingTemplate.shared
        nowPlaying.updateNowPlayingButtons([])
        buttonsPresenter.reset()
        nowPlaying.isUpNextButtonEnabled = false
        nowPlaying.remove(self)
    }

    /// A template-rendering button glyph; falls back to a filled circle so a bad symbol name can
    /// never produce an invisible, untappable button.
    private func buttonImage(_ symbolName: String, accessibilityLabel: String) -> UIImage {
        let image = UIImage(systemName: symbolName) ?? UIImage(systemName: "circle.fill")!
        image.accessibilityLabel = accessibilityLabel
        return image
    }

    /// Push the "Chapters" list for the current book — the Up Next button's destination.
    ///
    /// Built fresh per tap from the shared player's native chapter rows, so it reflects the loaded book
    /// at that moment without observing anything between taps.
    private func pushChapterList() {
        let player = Dependencies.shared.playerObserver
        let titles: [String?] = player.chapterRows.map(\.title)
        let rows = CarPlayChapterRows.chapters(titles: titles, currentIndex: player.chapterIndex)
        guard !rows.isEmpty else { return }

        let items = rows.prefix(CPListTemplate.maximumItemCount).map { row in
            let item = CPListItem(text: row.title, detailText: nil)
            item.isPlaying = row.isCurrent
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    Dependencies.shared.playerObserver.selectChapter(index: row.index)
                    self?.interfaceController?.popTemplate(animated: true) { _, error in
                        if let error { Log.error("CarPlay chapter-list pop failed", error: error) }
                        completion()
                    }
                }
            }
            return item
        }
        let list = CPListTemplate(
            title: String(localized: "player.chapters"),
            sections: [CPListSection(items: Array(items))]
        )
        interfaceController?.pushTemplate(list, animated: true) { _, error in
            if let error { Log.error("CarPlay chapter-list push failed", error: error) }
        }
    }

    /// A row's cover if it is already decoded, else nil.
    private func cachedArtwork(for row: CarPlayRow) -> UIImage? {
        row.coverPath.flatMap { rowArtwork[$0] }
    }

    /// CarPlay's list-image size in pixels: `CPListItem.maximumImageSize` at the car screen's own scale, because the
    /// head unit's display scale is independent of the phone's.
    private var coverPixelSize: Int {
        let carScale = interfaceController?.carTraitCollection.displayScale ?? 2
        let maxSize = CPListItem.maximumImageSize
        return Int(max(maxSize.width, maxSize.height) * carScale)
    }

    /// Resolve (and memoize) a Home row's cover at CarPlay's list-image size.
    ///
    /// Synchronous on purpose, and only for Home: its shelf tops out at eight rows of small local files the phone has
    /// already cached, so a placeholder-then-swap would buy nothing but flicker. The Library, which can run to the
    /// head unit's whole item limit, decodes off the main actor instead (`renderLibrary`).
    private func rowImage(for row: CarPlayRow) -> UIImage? {
        guard let path = row.coverPath else { return nil }
        if let cached = rowArtwork[path] { return cached }
        guard let image = ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: coverPixelSize) else {
            return nil
        }
        rowArtwork[path] = image
        return image
    }
}

extension CarPlaySceneDelegate: CPNowPlayingTemplateObserver {
    nonisolated func nowPlayingTemplateUpNextButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {
        Task { @MainActor in self.pushChapterList() }
    }
}
