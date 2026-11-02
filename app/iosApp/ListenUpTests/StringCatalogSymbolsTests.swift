import Foundation
import Testing
@testable import ListenUp

/// Pins that Xcode's generated String Catalog symbols resolve to the catalog's text.
struct StringCatalogSymbolsTests {
    @Test func positionalStringSymbolRendersCatalogValue() {
        #expect(String(localized: .libraryIndexJump("K")) == "Jump to K")
    }
}
