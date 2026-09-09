import Testing
@testable import ListenUp

struct AppTextFieldTests {
    @Test func leadingIconFallsBackByKind() {
        #expect(AppTextField.leadingIcon(explicit: nil, kind: .secure) == "lock")
        #expect(AppTextField.leadingIcon(explicit: nil, kind: .search) == "magnifyingglass")
        #expect(AppTextField.leadingIcon(explicit: nil, kind: .text) == nil)
    }
    @Test func explicitIconWinsOverKindDefault() {
        #expect(AppTextField.leadingIcon(explicit: "envelope", kind: .text) == "envelope")
        #expect(AppTextField.leadingIcon(explicit: "person", kind: .secure) == "person")
    }
    @Test func clearButtonOnlyForNonEmptySearch() {
        #expect(AppTextField.showsClearButton(kind: .search, text: "dune") == true)
        #expect(AppTextField.showsClearButton(kind: .search, text: "") == false)
        #expect(AppTextField.showsClearButton(kind: .text, text: "dune") == false)
        #expect(AppTextField.showsClearButton(kind: .secure, text: "dune") == false)
    }
    @Test func accessibilityIdentifierFromPlaceholder() {
        #expect(AppTextField.accessibilityIdentifier(placeholder: "Email Address") == "email_address_field")
        #expect(AppTextField.accessibilityIdentifier(placeholder: "Password") == "password_field")
    }
    @Test func submitLabelDefaultsToSearchForSearchKind() {
        #expect(AppTextField.defaultsToSearchSubmitLabel(explicit: nil, kind: .search) == true)
        #expect(AppTextField.defaultsToSearchSubmitLabel(explicit: nil, kind: .text) == false)
        #expect(AppTextField.defaultsToSearchSubmitLabel(explicit: nil, kind: .secure) == false)
        #expect(AppTextField.defaultsToSearchSubmitLabel(explicit: .done, kind: .search) == false)
    }
}

/// `TextEntry` is the one place a field's keyboard is decided, so its mapping is pinned here
/// case by case: what each content kind types on, what the system may autofill, how it
/// capitalizes, and whether the keyboard is allowed to correct it.
struct TextEntryTests {
    @Test func proseAndNamesOfThingsAreCorrectedAndCapitalized() {
        #expect(TextEntry.sentences.capitalization == .sentences)
        #expect(TextEntry.sentences.autocorrects == true)
        #expect(TextEntry.words.capitalization == .words)
        #expect(TextEntry.words.autocorrects == true)
        #expect(TextEntry.words.keyboardType == .default)
        #expect(TextEntry.words.contentType == nil)
    }

    @Test func personNamesCapitalizeWordsButAreNeverCorrected() {
        #expect(TextEntry.givenName.capitalization == .words)
        #expect(TextEntry.givenName.autocorrects == false)
        #expect(TextEntry.givenName.contentType == .givenName)
        #expect(TextEntry.familyName.contentType == .familyName)
    }

    @Test func machineShapedEntriesGetTheirKeyboardAndNoCorrection() {
        #expect(TextEntry.email.keyboardType == .emailAddress)
        #expect(TextEntry.email.contentType == .emailAddress)
        #expect(TextEntry.url.keyboardType == .URL)
        #expect(TextEntry.url.contentType == .URL)
        #expect(TextEntry.number.keyboardType == .numberPad)
        #expect(TextEntry.decimal.keyboardType == .decimalPad)
        for entry in [TextEntry.email, .url, .number, .decimal, .search, .password, .newPassword] {
            #expect(entry.capitalization == .never)
            #expect(entry.autocorrects == false)
        }
    }

    @Test func aSignUpEmailIsTheUsernameAutoFillPairsWithTheNewPassword() {
        #expect(TextEntry.accountEmail.keyboardType == .emailAddress)
        #expect(TextEntry.accountEmail.contentType == .username)
        #expect(TextEntry.accountEmail.autocorrects == false)
    }

    @Test func passwordsDistinguishExistingFromChosen() {
        #expect(TextEntry.password.contentType == .password)
        #expect(TextEntry.newPassword.contentType == .newPassword)
    }

    @Test func identifiersAreAsciiCapitals() {
        #expect(TextEntry.identifier.keyboardType == .asciiCapable)
        #expect(TextEntry.identifier.capitalization == .characters)
        #expect(TextEntry.identifier.autocorrects == false)
    }
}
