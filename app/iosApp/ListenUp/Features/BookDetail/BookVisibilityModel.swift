import Foundation
import Shared

/// A collection named in the Visibility section, and the destination it opens.
struct VisibilityCollection: Equatable, Hashable, Identifiable {
    let id: String
    let name: String
}

/// Who a restricted book is hidden from, as the section words it.
enum HiddenFromModel: Equatable {
    case nobody
    case everyone
    case members([String])
}

/// What Book Detail's Visibility section draws. Only these two states draw anything: a member
/// (no visibility), a public book and a held book (the inbox's held section says it) map to nil.
enum BookVisibilityModel: Equatable {
    case restricted(collections: [VisibilityCollection], hiddenFrom: HiddenFromModel)
    case stranded

    nonisolated static func from(_ visibility: BookVisibility?) -> BookVisibilityModel? {
        guard let visibility else { return nil }
        switch visibility.sealedType() {
        case .public, .held:
            return nil
        case .stranded:
            return .stranded
        case .restricted(let restrictedType):
            let restricted = restrictedType.value
            return .restricted(
                collections: restricted.collections.map { VisibilityCollection(id: $0.id, name: $0.name) },
                hiddenFrom: hiddenFrom(restricted.hiddenFrom)
            )
        }
    }

    // Deliberately no `default`: a new Kotlin case must fail to compile here.
    nonisolated static func hiddenFrom(_ hiddenFrom: HiddenFrom) -> HiddenFromModel {
        switch hiddenFrom.sealedType() {
        case .nobody: return .nobody
        case .everyone: return .everyone
        case .members(let membersType): return .members(membersType.value.names)
        }
    }
}

/// The section's words, from the shared catalog; pure so it is tested without a view.
enum VisibilityCopy {
    nonisolated static func headline(_ hiddenFrom: HiddenFromModel, expanded: Bool) -> String {
        switch hiddenFrom {
        case .nobody: return String(localized: "book.visibility_every_member")
        case .everyone: return String(localized: "book.visibility_hidden_from_all")
        case .members(let names):
            return String(format: String(localized: "book.visibility_hidden_from"), nameList(names, expanded: expanded))
        }
    }

    /// "Alice", "Alice and Ben", "Alice, Ben and Cy", or "Alice, Dev, Hana and 2 others" — cut by
    /// the shared `HiddenFromNames`, so every platform stops at the same place.
    nonisolated static func nameList(_ names: [String], expanded: Bool) -> String {
        let summary = HiddenFromNames.shared.summarize(names: names, expanded: expanded)
        let shown = summary.shown
        // Stays Int32: the catalog's `%2$d` reads a C int, so the argument matches it exactly.
        let others = summary.othersCount
        if others == 1 {
            return String(format: String(localized: "book.visibility_names_one_other"), shown.joined(separator: ", "))
        }
        if others > 1 {
            let list = shown.joined(separator: ", ")
            return String(format: String(localized: "book.visibility_names_others"), list, others)
        }
        if shown.count == 1 { return shown[0] }
        return String(
            format: String(localized: "book.visibility_names_two"),
            shown.dropLast().joined(separator: ", "),
            shown[shown.count - 1]
        )
    }

    nonisolated static func canExpand(_ names: [String], expanded: Bool) -> Bool {
        HiddenFromNames.shared.canExpand(names: names, expanded: expanded)
    }

    nonisolated static func reason(_ hiddenFrom: HiddenFromModel, collections: [VisibilityCollection]) -> String {
        let only = collections.count == 1 ? collections[0].name : nil
        switch hiddenFrom {
        case .members:
            return only.map { String(format: String(localized: "book.visibility_reason_members_one"), $0) }
                ?? String(localized: "book.visibility_reason_members_many")
        case .nobody:
            return only.map { String(format: String(localized: "book.visibility_reason_nobody_one"), $0) }
                ?? String(localized: "book.visibility_reason_nobody_many")
        case .everyone:
            return only.map { String(format: String(localized: "book.visibility_reason_everyone_one"), $0) }
                ?? String(localized: "book.visibility_reason_everyone_many")
        }
    }
}
