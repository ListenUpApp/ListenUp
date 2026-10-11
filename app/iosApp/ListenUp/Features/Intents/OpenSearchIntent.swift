import AppIntents

/// "Search ListenUp" — opens the app on Search with the cursor in its field, the iOS counterpart of
/// the Android launcher's Search shortcut.
///
/// Reaches the tab shell the way a book link does: through the process's one `DeepLinkRouter`, which
/// hands the request to the front window (and holds it across a cold launch until a shell exists).
struct OpenSearchIntent: AppIntent {
    static let title: LocalizedStringResource = "Search"
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        DeepLinkRouter.shared.requestSearch()
        return .result()
    }
}
