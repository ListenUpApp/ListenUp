import Foundation
import Shared

/// Admin → Hardcover (#1542), flattened from the shared `HardcoverSourceStatus` and `HardcoverTokenSave`
/// into the values the section binds to. It never holds the token: only whose it is.
struct HardcoverSourceModel: Equatable {
    /// The API token's state, described by its owner.
    enum Token: Equatable {
        case notSet
        case saved(username: String)
        case rejected(username: String)
    }

    /// Hardcover's API page, where an admin gets a token.
    nonisolated static let apiPage = URL(string: "https://hardcover.app/account/api")

    let token: Token
    let metadataEnabled: Bool
    /// True when no token can read Hardcover's catalogue: no API token, and nobody connected.
    let metadataUnavailable: Bool
    /// A token is being checked with Hardcover, or removed.
    let isBusy: Bool
    /// Why the last save was refused, shown under the field.
    let refusal: String?

    /// "Set · belongs to @simon", while a token Hardcover accepted is stored.
    nonisolated var savedLine: String? {
        guard case .saved(let username) = token else { return nil }
        return String(format: String(localized: "admin.hardcover_token_set"), username)
    }

    /// The metadata switch's subtitle: what it does, or what it needs first.
    nonisolated var metadataSubtitle: String {
        metadataUnavailable
            ? String(localized: "admin.hardcover_metadata_unavailable")
            : String(localized: "admin.hardcover_metadata_subtitle")
    }

    /// Pure mapping from the Swift Export-bridged shared types; `nonisolated` so tests need no main actor.
    nonisolated static func from(_ status: HardcoverSourceStatus, save: HardcoverTokenSave) -> HardcoverSourceModel {
        HardcoverSourceModel(
            token: token(from: status.apiToken),
            metadataEnabled: status.metadataEnabled,
            metadataUnavailable: status.metadataUnavailable != nil,
            isBusy: isBusy(save),
            refusal: refusal(save)
        )
    }

    private nonisolated static func token(from status: HardcoverApiTokenStatus) -> Token {
        switch status.sealedType() {
        case .notSet: .notSet
        case .saved(let savedType): .saved(username: savedType.value.username)
        case .rejected(let rejectedType): .rejected(username: rejectedType.value.username)
        }
    }

    private nonisolated static func isBusy(_ save: HardcoverTokenSave) -> Bool {
        if case .busy = save.sealedType() { return true }
        return false
    }

    private nonisolated static func refusal(_ save: HardcoverTokenSave) -> String? {
        if case .refused(let refusedType) = save.sealedType() { return refusedType.value.error.message }
        return nil
    }
}
