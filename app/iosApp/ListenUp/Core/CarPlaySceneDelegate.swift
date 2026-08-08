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
/// The browse tree mirrors the phone app (`MainTabView`), not Android Auto: Home is the
/// continue-listening shelf, which is overwhelmingly what a driver wants. Library and Search are
/// the next slice. Discover is deliberately absent — it is mostly a leaderboard and a friend
/// activity feed, which is the wrong thing to put in front of someone driving.
@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    /// The template handed to `setRootTemplate` on connect.
    ///
    /// `CPNowPlayingTemplate` must never appear in here: CarPlay's `validateTemplates:` rejects
    /// it as a tab bar member with an `NSInvalidArgumentException` the moment the head unit
    /// connects (allowed classes are the browse templates — list, grid, information,
    /// point-of-interest). Now-playing is reached by *pushing* `CPNowPlayingTemplate.shared`,
    /// as the row handler does, and the system adds its own Now Playing affordance while audio
    /// plays. When Library and Search arrive, this becomes a `CPTabBarTemplate` of list
    /// templates — the constraint stays.
    static func rootTemplate(home: CPListTemplate) -> CPTemplate {
        home
    }

    /// Valid only between connect and disconnect, so it is held weakly and every use is optional.
    private weak var interfaceController: CPInterfaceController?

    /// Built on connect and released on disconnect: its `isolated deinit` closes the underlying
    /// Kotlin ViewModel, whose stream jobs would otherwise outlive the car session (#1192).
    private var home: HomeViewModelWrapper?

    private let homeTemplate = CPListTemplate(
        title: String(localized: "home.continue_listening"),
        sections: []
    )

    /// Downsampled row artwork, keyed by cover path. At most one entry per browse row, and the
    /// whole cache lives only for the car session — released with the delegate's other per-session
    /// state on disconnect.
    private var rowArtwork: [String: UIImage] = [:]

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController

        let home = HomeViewModelWrapper()
        self.home = home
        observeHome(home)

        interfaceController.setRootTemplate(Self.rootTemplate(home: homeTemplate), animated: false) { _, error in
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
        rowArtwork.removeAll()
        Log.info("CarPlay disconnected")
    }

    /// Re-render the Home list whenever the shared Home state changes.
    ///
    /// `withObservationTracking` fires once per change, so it re-arms itself — the Observation
    /// equivalent of what SwiftUI does implicitly for a view body. There is no view here to do
    /// it for us, and without re-arming the car would show whatever the library looked like at
    /// the moment the driver plugged in.
    private func observeHome(_ home: HomeViewModelWrapper) {
        withObservationTracking {
            render(home.phase)
        } onChange: { [weak self, weak home] in
            Task { @MainActor in
                guard let self, let home else { return }
                self.observeHome(home)
            }
        }
    }

    private func render(_ phase: HomePhase) {
        let items: [CPListItem]
        switch phase {
        case .loading:
            items = []
        case .ready(let ready):
            items = CarPlayRows.continueListening(from: ready.continueItems).map(listItem(for:))
        case .error(let message):
            // Honest over silent: a driver seeing an empty list cannot tell "nothing to resume"
            // from "we failed to load". Untappable, so it cannot lead anywhere.
            let row = CPListItem(text: message, detailText: nil)
            row.handler = { _, completion in completion() }
            items = [row]
        }
        homeTemplate.updateSections([CPListSection(items: items)])
    }

    private func listItem(for row: CarPlayRow) -> CPListItem {
        let item = CPListItem(text: row.title, detailText: row.detailText, image: rowImage(for: row))
        item.handler = { [weak self] _, completion in
            Dependencies.shared.playerCoordinator.play(bookId: row.id)
            // Push now-playing so the driver lands on transport controls rather than staying in
            // a list they have to navigate out of.
            self?.interfaceController?.pushTemplate(CPNowPlayingTemplate.shared, animated: true) { _, error in
                if let error { Log.error("CarPlay now-playing push failed", error: error) }
                completion()
            }
        }
        return item
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
