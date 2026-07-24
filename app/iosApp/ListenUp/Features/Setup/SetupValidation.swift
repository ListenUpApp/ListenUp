import Foundation
import Shared

/// Pure, UI-independent rules for the Setup form (unit-testable without a live KMP ViewModel).
enum SetupFieldKey: Equatable {
    case firstName, lastName, email, password, passwordConfirm
}

enum SetupValidation {
    /// Returns `true` when the confirm field has been typed and doesn't match the password.
    /// Empty confirm is not flagged — the user hasn't finished yet.
    static func passwordMismatch(password: String, confirm: String) -> Bool {
        !confirm.isEmpty && password != confirm
    }

    /// Maps a KMP `SetupField` to the Swift-native `SetupFieldKey` for error highlighting.
    static func errorField(for field: SetupField) -> SetupFieldKey {
        switch field {
        case .firstName: return .firstName
        case .lastName: return .lastName
        case .email: return .email
        case .password: return .password
        case .passwordConfirm: return .passwordConfirm
        }
    }
}
