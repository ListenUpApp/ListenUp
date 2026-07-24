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
