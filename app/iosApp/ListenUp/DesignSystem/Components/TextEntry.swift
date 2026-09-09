import SwiftUI
import UIKit

/// What a text field holds. One value decides the whole keyboard — type, content hint,
/// capitalization, autocorrection — so a call site names the content and never the mechanics,
/// and no field can ship with the wrong keyboard because it forgot one of four knobs.
///
/// Required on every `AppTextField`: the compiler is the guard, the way `keyboardOptions` is
/// pinned by a Konsist rule on the Compose side (#1405). The choices mirror that pass field for
/// field — titles and names of things capitalize words, prose capitalizes sentences, identifiers
/// are ASCII capitals, and everything machine-shaped (email, URL, password, search, numbers)
/// gets no capitalization and no autocorrection.
enum TextEntry: Equatable {
    /// Titles, names of things, publishers: capitalized words, autocorrection on.
    case words
    /// Prose — descriptions, bios, taglines: sentences, autocorrection on.
    case sentences
    /// A person's given name: words, `.givenName`, no autocorrection (proper nouns get mangled).
    case givenName
    /// A person's family name: words, `.familyName`, no autocorrection.
    case familyName
    case email
    /// The email that will BE the account's username, on a sign-up form: the email keyboard, but the
    /// `.username` content type, which is what iOS Password AutoFill pairs with the `.newPassword`
    /// fields beside it so a tapped credential suggestion actually fills.
    case accountEmail
    /// An existing password (sign in, current password).
    case password
    /// A password being chosen (sign up, change password) — the system may offer a strong one.
    case newPassword
    case url
    /// Whole numbers — a year.
    case number
    /// Numbers with a fraction — a series position.
    case decimal
    /// ISBN, ASIN, invite codes: ASCII keyboard, capitals, no autocorrection.
    case identifier
    /// A search query: nothing corrected, nothing capitalized.
    case search

    /// Capitalization as an `Equatable` value the tests can pin; `TextInputAutocapitalization`
    /// itself is not comparable.
    enum Capitalization: Equatable {
        case never, words, sentences, characters

        var textInput: TextInputAutocapitalization {
            switch self {
            case .never: .never
            case .words: .words
            case .sentences: .sentences
            case .characters: .characters
            }
        }
    }

    var keyboardType: UIKeyboardType {
        switch self {
        case .email, .accountEmail: .emailAddress
        case .url: .URL
        case .number: .numberPad
        case .decimal: .decimalPad
        case .identifier: .asciiCapable
        case .words, .sentences, .givenName, .familyName, .password, .newPassword, .search: .default
        }
    }

    var contentType: UITextContentType? {
        switch self {
        case .givenName: .givenName
        case .familyName: .familyName
        case .email: .emailAddress
        case .accountEmail: .username
        case .password: .password
        case .newPassword: .newPassword
        case .url: .URL
        case .words, .sentences, .number, .decimal, .identifier, .search: nil
        }
    }

    var capitalization: Capitalization {
        switch self {
        case .words, .givenName, .familyName: .words
        case .sentences: .sentences
        case .identifier: .characters
        case .email, .accountEmail, .password, .newPassword, .url, .number, .decimal, .search: .never
        }
    }

    /// Only human prose and names of things want the keyboard fixing their spelling.
    var autocorrects: Bool {
        switch self {
        case .words, .sentences: true
        case .givenName, .familyName, .email, .accountEmail, .password, .newPassword, .url, .number, .decimal,
             .identifier, .search: false
        }
    }
}
