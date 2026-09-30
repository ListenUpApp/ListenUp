import Foundation

extension SyncSessionController {
    /// The process's one sync session, over the shared `SyncRepository` and `DownloadService`.
    ///
    /// App-scoped rather than per window: sync is a property of the signed-in process, not of a
    /// window, and each iPad window used to build its own controller. The work is single-flight
    /// either way; one owner makes that true by construction rather than by the engine's grace.
    static let shared = makeShared()

    private static func makeShared() -> SyncSessionController {
        SyncSessionController(
            connectRealtime: {
                do {
                    try await Dependencies.shared.syncRepository.connectRealtime()
                } catch is CancellationError {
                } catch {
                    // Realtime sync is best-effort: pull-to-refresh is the manual fallback
                    // (Never Stranded), but the failure must not vanish — log it.
                    Log.error("Realtime sync connect failed", error: error)
                }
            },
            resumeDownloads: {
                do {
                    try await Dependencies.shared.downloadService.resumeIncompleteDownloads()
                } catch is CancellationError {
                } catch {
                    Log.error("Resume incomplete downloads failed", error: error)
                }
            }
        )
    }
}
