import Foundation
import Shared

/// One command in Book Detail's More menu. Native projection of the shared `BookDetailMenuItem`.
enum BookDetailMenuCommand: Hashable {
    case share, markFinished, restart, markNotStarted, editDetails, matchDetails, editChapters, addToCollection,
         deleteFromServer

    // Deliberately no `default`: a new Kotlin item must fail to compile here.
    init(_ item: BookDetailMenuItem) {
        switch item.sealedType() {
        case .share: self = .share
        case .markFinished: self = .markFinished
        case .restart: self = .restart
        case .markNotStarted: self = .markNotStarted
        case .editDetails: self = .editDetails
        case .matchDetails: self = .matchDetails
        case .editChapters: self = .editChapters
        case .addToCollection: self = .addToCollection
        case .deleteFromServer: self = .deleteFromServer
        }
    }

    /// HIG title case; an ellipsis where the command asks or opens something first.
    var title: String {
        switch self {
        case .share: String(localized: "book.detail_menu_share_ios")
        case .markFinished: String(localized: "book.detail_menu_mark_finished_ios")
        case .restart: String(localized: "book.detail_menu_restart_ios")
        case .markNotStarted: String(localized: "book.detail_menu_mark_not_started_ios")
        case .editDetails: String(localized: "library.menu_edit_details_ios")
        case .matchDetails: String(localized: "book.detail_menu_match_details_ios")
        case .editChapters: String(localized: "book.detail_menu_edit_chapters_ios")
        case .addToCollection: String(localized: "library.menu_add_to_collection_ios")
        case .deleteFromServer: String(localized: "book.detail_menu_delete_ios")
        }
    }

    var systemImage: String {
        switch self {
        case .share: "square.and.arrow.up"
        case .markFinished: "checkmark.circle"
        case .restart: "backward.end"
        case .markNotStarted: "arrow.counterclockwise"
        case .editDetails: "pencil"
        case .matchDetails: "sparkles"
        case .editChapters: "list.bullet.indent"
        case .addToCollection: "rectangle.stack.badge.plus"
        case .deleteFromServer: "trash"
        }
    }

    /// Only Delete destroys anything: Mark as Not Started asks first, but loses only a position (HIG, Menus).
    var isDestructive: Bool { self == .deleteFromServer }

    /// Mark as Finished, Start Over and Mark as Not Started wait while one of them is in flight.
    var waitsOnProgress: Bool { self == .markFinished || self == .restart || self == .markNotStarted }
}

/// A group's heading. Native projection of the shared `BookDetailMenuHeading`.
enum BookDetailMenuHeadingKind: Hashable {
    case progress, edit, admin

    init(_ heading: BookDetailMenuHeading) {
        switch heading.sealedType() {
        case .progress: self = .progress
        case .edit: self = .edit
        case .admin: self = .admin
        }
    }

    var title: String {
        switch self {
        case .progress: String(localized: "book.detail_menu_progress")
        case .edit: String(localized: "book.detail_menu_edit")
        case .admin: String(localized: "library.menu_admin")
        }
    }
}

/// One section of the More menu.
struct BookDetailMenuSection: Hashable {
    let heading: BookDetailMenuHeadingKind?
    let commands: [BookDetailMenuCommand]
    let isDestructive: Bool

    init(heading: BookDetailMenuHeadingKind?, commands: [BookDetailMenuCommand], isDestructive: Bool) {
        self.heading = heading
        self.commands = commands
        self.isDestructive = isDestructive
    }

    init(_ group: BookDetailMenuGroup) {
        self.init(heading: group.heading.map(BookDetailMenuHeadingKind.init),
                  commands: group.items.map(BookDetailMenuCommand.init),
                  isDestructive: group.isDestructive)
    }
}

/// The More menu's sections for this device: the shared rule's groups, less Share while the server has not named
/// itself (no link to share), and never an empty section.
enum BookDetailMenuModel {
    nonisolated static func sections(_ groups: [BookDetailMenuSection], canShare: Bool) -> [BookDetailMenuSection] {
        groups
            .map { section in
                canShare ? section : BookDetailMenuSection(
                    heading: section.heading,
                    commands: section.commands.filter { $0 != .share },
                    isDestructive: section.isDestructive)
            }
            .filter { !$0.commands.isEmpty }
    }
}
