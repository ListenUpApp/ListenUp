import SwiftUI
import Shared

/// Render phase for the notification-prefs screen, flattened from `NotificationPrefsUiState`.
enum NotificationPrefsPhase {
    case loading
    case ready([NotificationPrefRowModel])
    case error(String)
}

/// One registry type's resolved preference, native — the bridged DTOs never reach a `ForEach`.
/// Display-name selection happens HERE: an unknown type key yields nil and the row is filtered
/// (mirrors Android — a newer server's types wait for the client update; there is nothing to
/// toggle blind).
struct NotificationPrefRowModel: Identifiable, Equatable {
    let type: String
    let displayName: String
    let inApp: Bool
    let push: Bool
    let pushEligible: Bool

    var id: String { type }

    init?(from dto: NotificationPreferenceDto) {
        guard let name = Self.displayName(for: dto.type) else { return nil }
        type = dto.type
        displayName = name
        inApp = dto.preference.inApp
        push = dto.preference.push
        pushEligible = dto.pushEligible
    }

    /// The known registry types, hard-coded: `String(localized:)` cannot signal a missing key at
    /// runtime (it echoes the key), so unknown-type filtering needs an explicit switch.
    private static func displayName(for type: String) -> String? {
        switch type {
        case "campfire_invite": String(localized: "notifications.type_campfire_invite")
        case "registration_decision": String(localized: "notifications.type_registration_decision")
        case "registration_approval": String(localized: "notifications.type_registration_approval")
        default: nil
        }
    }
}

/// Observes `NotificationPrefsViewModel`, flattening its state into a native phase and forwarding
/// toggle writes. The ViewModel applies toggles optimistically and reverts on server refusal, so
/// there is no rollback here; the per-toggle `busy` marks ride until the next state emission,
/// feeding `ToggleRow.isBusy` so a second flip cannot land before the write reflects.
/// Mirrors `DevicesObserver`.
@Observable
@MainActor
final class NotificationPrefsObserver {
    private(set) var phase: NotificationPrefsPhase = .loading

    /// Toggles with a write in flight, keyed "<type>:inApp" / "<type>:push".
    private var busy: Set<String> = []

    private let viewModel: NotificationPrefsViewModel
    private let bridge = FlowBridge()

    init(viewModel: NotificationPrefsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func refresh() { viewModel.refresh() }

    func setInApp(type: String, isOn: Bool) {
        guard let row = row(type) else { return }
        busy.insert(Self.busyKey(type: type, push: false))
        viewModel.setPreference(type: type, preference: NotificationPreference(inApp: isOn, push: row.push))
    }

    func setPush(type: String, isOn: Bool) {
        guard let row = row(type) else { return }
        busy.insert(Self.busyKey(type: type, push: true))
        viewModel.setPreference(type: type, preference: NotificationPreference(inApp: row.inApp, push: isOn))
    }

    func isBusy(type: String, push: Bool) -> Bool {
        busy.contains(Self.busyKey(type: type, push: push))
    }

    // MARK: - State mapping

    private func apply(_ state: NotificationPrefsUiState) {
        busy = []
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .data(let data):
            phase = .ready(data.prefs.compactMap { NotificationPrefRowModel(from: $0) })
        case .error(let error):
            phase = .error(error.error.message)
        case .unknown:
            Log.error("Unexpected NotificationPrefsUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }

    private func row(_ type: String) -> NotificationPrefRowModel? {
        guard case .ready(let rows) = phase else { return nil }
        return rows.first { $0.type == type }
    }

    private static func busyKey(type: String, push: Bool) -> String {
        "\(type):\(push ? "push" : "inApp")"
    }
}
