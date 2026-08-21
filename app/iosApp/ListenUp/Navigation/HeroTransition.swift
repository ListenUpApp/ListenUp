import SwiftUI

// Hero (zoom) transitions between a list cell and the detail page it opens.
//
// SwiftUI has two halves to pair: `.matchedTransitionSource(id:in:)` marks the cell, and
// `.navigationTransition(.zoom(sourceID:in:))` marks the pushed destination. Both need the
// same `Namespace.ID`, which `MainTabView` owns and publishes below.
//
// Note this is NOT `matchedGeometryEffect` — that only morphs within a single view hierarchy
// (as `PlayerMorph` does for mini ↔ full player) and does nothing across a NavigationStack push.

extension EnvironmentValues {
    /// The namespace pairing a list cell with the detail page it zooms into. Provided by
    /// `MainTabView`; `nil` anywhere outside it, which degrades to a plain push.
    @Entry var heroNamespace: Namespace.ID? = nil
}

/// The hero identity for a book's cover art.
///
/// Namespaced by entity kind so a book and a contributor sharing an id can never collide.
func bookCoverHeroID(_ bookId: String) -> String { "hero:book:\(bookId)" }

/// The hero identity for a contributor's portrait.
func contributorHeroID(_ contributorId: String) -> String { "hero:contributor:\(contributorId)" }

/// The hero identity for a series' cover art.
func seriesHeroID(_ seriesId: String) -> String { "hero:series:\(seriesId)" }

extension View {
    /// Marks this view as the cell a hero transition flies *from*.
    ///
    /// `id` must match the `heroDestination(_:)` on the page it opens, and must be unique among
    /// the sources on screen at once.
    @ViewBuilder
    func heroSource(_ id: String) -> some View {
        HeroSourceModifier(id: id, content: self)
    }

    /// Marks this view as the page a hero transition flies *to*.
    @ViewBuilder
    func heroDestination(_ id: String) -> some View {
        HeroDestinationModifier(id: id, content: self)
    }
}

/// Reads the namespace from the environment so `heroSource` stays a no-op outside `MainTabView`.
private struct HeroSourceModifier<Content: View>: View {
    @Environment(\.heroNamespace) private var namespace
    let id: String
    let content: Content

    var body: some View {
        if let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
    }
}

/// Reads the namespace from the environment so `heroDestination` stays a no-op outside `MainTabView`.
private struct HeroDestinationModifier<Content: View>: View {
    @Environment(\.heroNamespace) private var namespace
    let id: String
    let content: Content

    var body: some View {
        if let namespace {
            content.navigationTransition(.zoom(sourceID: id, in: namespace))
        } else {
            content
        }
    }
}
