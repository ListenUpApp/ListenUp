import Foundation

/// The More menu as this device draws it. Split out of `BookDetailObserver.swift`, which sits near SwiftLint's
/// type-body cap; the stored projections stay on the class, because stored properties cannot live in an extension.
extension BookDetailObserver {
    /// The More menu's sections: the shared rule's groups, less Share while the server has not named itself.
    var menu: [BookDetailMenuSection] { BookDetailMenuModel.sections(menuGroups, canShare: shareURL != nil) }
}
