import Shared

/// The one place SwiftUI names a permission, group, preset or access label.
enum PermissionLabels {
    static func title(_ permission: Permission) -> String {
        switch permission {
        case .editMetadata: String(localized: "admin.permission_edit_metadata")
        case .curateLibrary: String(localized: "admin.permission_curate_library")
        case .contributeStoryWorld: String(localized: "admin.permission_contribute_story_world")
        case .curateStoryWorld: String(localized: "admin.permission_curate_story_world")
        case .makeReadingOrders: String(localized: "admin.permission_make_reading_orders")
        case .unknown: ""
        }
    }

    static func description(_ permission: Permission) -> String {
        switch permission {
        case .editMetadata: String(localized: "admin.permission_edit_metadata_description")
        case .curateLibrary: String(localized: "admin.permission_curate_library_description")
        case .contributeStoryWorld: String(localized: "admin.permission_contribute_story_world_description")
        case .curateStoryWorld: String(localized: "admin.permission_curate_story_world_description")
        case .makeReadingOrders: String(localized: "admin.permission_make_reading_orders_description")
        case .unknown: ""
        }
    }

    static func title(_ group: PermissionGroup) -> String {
        switch group {
        case .library: String(localized: "admin.permission_group_library")
        case .storyWorld: String(localized: "admin.permission_group_story_world")
        case .readingOrders: String(localized: "admin.permission_group_reading_orders")
        case .unknown: ""
        }
    }

    static func title(_ preset: PermissionPreset) -> String {
        switch preset {
        case .listener: String(localized: "admin.preset_listener")
        case .contributor: String(localized: "admin.preset_contributor")
        case .librarian: String(localized: "admin.preset_librarian")
        case .custom: String(localized: "admin.preset_custom")
        }
    }

    static func description(_ preset: PermissionPreset) -> String {
        switch preset {
        case .listener: String(localized: "admin.preset_listener_description")
        case .contributor: String(localized: "admin.preset_contributor_description")
        case .librarian: String(localized: "admin.preset_librarian_description")
        case .custom: String(localized: "admin.preset_custom_description")
        }
    }

    static func title(_ access: AccessLabel) -> String {
        switch access {
        case .owner: String(localized: "admin.role_owner")
        case .admin: String(localized: "common.admin")
        case .member: String(localized: "common.member")
        case .listener: String(localized: "admin.preset_listener")
        case .contributor: String(localized: "admin.preset_contributor")
        case .librarian: String(localized: "admin.preset_librarian")
        case .custom: String(localized: "admin.preset_custom")
        }
    }
}
