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

    private let libraryTemplate: CPListTemplate = {
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
        home = nil
        library = nil
        rowArtwork.removeAll()
        resetNowPlaying()
        Log.info("CarPlay disconnected")
    }

    /// Re-render the Home list whenever the books in progress change.
    ///
    /// `withObservationTracking` fires once per change, so it re-arms itself — the Observation
    /// equivalent of what SwiftUI does implicitly for a view body. There is no view here to do
    /// it for us, and without re-arming the car would show whatever the library looked like at
    /// the moment the driver plugged in.
    private func observeHome(_ home: CarPlayHomeWrapper) {
        withObservationTracking {
            render(home)
        } onChange: { [weak self, weak home] in
            Task { @MainActor in
                guard let self, let home else { return }
                self.observeHome(home)
            }
        }
    }

    private func render(_ home: CarPlayHomeWrapper) {
        let items: [CPListItem]
        if let message = home.failureMessage {
            // Honest over silent: a driver seeing an empty list cannot tell "nothing to resume"
            // from "we failed to load". Untappable, so it cannot lead anywhere.
            let row = CPListItem(text: message, detailText: nil)
            row.handler = { _, completion in completion() }
            items = [row]
        } else {
            items = CarPlayRows.continueListening(from: home.items).map(listItem(for:))
        }
        homeTemplate.updateSections([CPListSection(items: items)])
    }

    /// Re-render the Library list whenever the sorted book list changes — same re-arming
    /// `withObservationTracking` shape as `observeHome`.
    private func observeLibrary(_ library: CarPlayLibraryWrapper) {
        withObservationTracking {
            renderLibrary(library.books)
        } onChange: { [weak self, weak library] in
            Task { @MainActor in
                guard let self, let library else { return }
                self.observeLibrary(library)
            }
        }
    }

    private func renderLibrary(_ books: [BookRow]) {
        let rows = CarPlayRows.library(from: books, limit: CPListTemplate.maximumItemCount)
        libraryTemplate.updateSections([CPListSection(items: rows.map(listItem(for:)))])
    }

    private func listItem(for row: CarPlayRow) -> CPListItem {
        let item = CPListItem(text: row.title, detailText: row.detailText, image: rowImage(for: row))
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
        observeNextChapter()
        nowPlaying.isUpNextButtonEnabled = true
        nowPlaying.upNextTitle = String(localized: "player.chapters")
        nowPlaying.add(self)
    }

    /// Re-draw the buttons whenever next becomes possible or impossible, re-arming like `observeHome`. Stops once the
    /// car disconnects.
    private func observeNextChapter() {
        guard interfaceController != nil else { return }
        withObservationTracking {
            updateNowPlayingButtons(hasNextChapter: Dependencies.shared.playerObserver.canSkipToNextChapter)
        } onChange: { [weak self] in
            Task { @MainActor in self?.observeNextChapter() }
        }
    }

    private func updateNowPlayingButtons(hasNextChapter: Bool) {
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
            button.isEnabled = spec.isEnabled(hasNextChapter: hasNextChapter)
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

    /// Resolve (and memoize) a row's cover at CarPlay's list-image size.
    ///
    /// Synchronous on purpose: covers are small local files the phone has already cached, and the
    /// shelf tops out at eight rows — a placeholder-then-swap pipeline would buy nothing but
    /// flicker. Sized to `CPListItem.maximumImageSize` at the car screen's own scale, because the
    /// head unit's display scale is independent of the phone's.
    private func rowImage(for row: CarPlayRow) -> UIImage? {
        guard let path = row.coverPath else { return nil }
        if let cached = rowArtwork[path] { return cached }
        let carScale = interfaceController?.carTraitCollection.displayScale ?? 2
        let maxSize = CPListItem.maximumImageSize
        let maxPixels = Int(max(maxSize.width, maxSize.height) * carScale)
        guard let image = ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: maxPixels) else {
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
