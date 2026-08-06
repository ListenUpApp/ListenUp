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
/// would just make the two disagree.
///
/// Browse templates are the next slice; this sets now-playing as the root so the scene, the
/// entitlement and the existing now-playing plumbing can be verified end to end before any
/// browse-tree volume is committed to.
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    /// Valid only between connect and disconnect, so it is held weakly and every use is optional.
    private weak var interfaceController: CPInterfaceController?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        interfaceController.setRootTemplate(CPNowPlayingTemplate.shared, animated: false) { done, error in
            if let error {
                Log.error("CarPlay root template failed", error: error)
            } else {
                Log.info("CarPlay connected (root set: \(done))")
            }
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
        Log.info("CarPlay disconnected")
    }
}
