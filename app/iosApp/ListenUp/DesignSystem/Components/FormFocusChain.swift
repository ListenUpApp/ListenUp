import SwiftUI

/// A form's text fields in the order Return walks them.
///
/// HIG, Virtual keyboards: "Consider customizing the Return key type if it helps clarify the
/// text-entry experience" — every field but the last shows Next and moves focus on; the last shows
/// the form's own verb (Go, Join, Done) and either submits or puts the keyboard away. The order
/// lives in one pure value per form so it is tested, rather than scattered through `onSubmit`s.
///
/// The default chain is declaration order (`allCases`). A form whose fields are not one run — Book
/// Edit's identity fields and its catalog fields sit a screen apart — overrides `next`.
protocol FormFocusChain: CaseIterable, Hashable where AllCases == [Self] {
    /// The field Return moves to, or nil when this field ends its run.
    var next: Self? { get }
}

extension FormFocusChain {
    var next: Self? { Self.defaultNext(after: self) }

    /// Declaration order: the case after `field`, or nil for the last.
    static func defaultNext(after field: Self) -> Self? {
        let all = allCases
        guard let index = all.firstIndex(of: field), all.index(after: index) < all.endIndex else { return nil }
        return all[all.index(after: index)]
    }

    /// Next while the chain goes on; the form's own `last` label where it ends.
    func submitLabel(last: SubmitLabel) -> SubmitLabel { next == nil ? last : .next }
}

/// What Return does in a chained field: move to the next field, or — at the end of the run — hand
/// over to `onEnd` (submit the form, or clear focus to dismiss the keyboard).
@MainActor
enum FormFocus {
    static func advance<Field: FormFocusChain>(
        from field: Field,
        focus: FocusState<Field?>.Binding,
        onEnd: () -> Void
    ) {
        advance(from: field, moveTo: { focus.wrappedValue = $0 }, onEnd: onEnd)
    }

    /// The rule itself, over a plain setter — `FocusState.Binding` only exists inside a view.
    static func advance<Field: FormFocusChain>(
        from field: Field,
        moveTo: (Field) -> Void,
        onEnd: () -> Void
    ) {
        if let next = field.next {
            moveTo(next)
        } else {
            onEnd()
        }
    }
}

// MARK: - The app's chained forms

/// Sign In: Email → Password; Return in Password signs in.
enum LoginFocusField: FormFocusChain { case email, password }

/// Create Account: names, email, then the two passwords; Return in Confirm creates the account.
enum RegisterFocusField: FormFocusChain { case firstName, lastName, email, password, confirmPassword }

/// First-run admin setup — the same shape as Create Account.
enum SetupFocusField: FormFocusChain { case firstName, lastName, email, password, confirmPassword }

/// Claim Invite's password step: names, then the password; Return in Password joins.
enum ClaimInviteFocusField: FormFocusChain { case firstName, lastName, password }

/// Edit Profile: two independent runs — the name pair and the password trio. The tagline stands
/// alone. Every run ends by putting the keyboard away; saving stays on the Done button.
enum EditProfileFocusField: FormFocusChain {
    case tagline, firstName, lastName, currentPassword, newPassword, confirmPassword

    var next: EditProfileFocusField? {
        switch self {
        case .firstName: .lastName
        case .currentPassword: .newPassword
        case .newPassword: .confirmPassword
        case .tagline, .lastName, .confirmPassword: nil
        }
    }
}

/// Book Edit: the identity run (Title → Subtitle → Sort Title; the multi-line Description takes
/// Return as a newline, so it is not in the chain) and the catalog run (Publisher → Year, ISBN →
/// ASIN; Year's number pad has no Return key, so it ends its run).
enum BookEditFocusField: FormFocusChain {
    case title, subtitle, sortTitle, publisher, year, isbn, asin

    var next: BookEditFocusField? {
        switch self {
        case .title: .subtitle
        case .subtitle: .sortTitle
        case .publisher: .year
        case .isbn: .asin
        case .sortTitle, .year, .asin: nil
        }
    }
}
