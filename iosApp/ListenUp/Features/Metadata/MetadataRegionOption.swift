import Shared

/// A metadata-lookup region choice, mapped to a native Swift value at the observer boundary so the
/// region pickers never feed a Swift-Export-bridged `MetadataLocale` into a `ForEach` (per
/// `iosApp/CLAUDE.md` rule 8). It carries the raw `region`/`language` tokens so it round-trips back
/// to a `MetadataLocale` for `changeRegion` without re-reading across the Kotlin seam per diff.
struct MetadataRegionOption: Identifiable, Hashable {
    let region: String
    let language: String?
    let displayName: String

    var id: String { region }

    init(_ locale: MetadataLocale) {
        region = locale.region
        language = locale.language
        displayName = locale.displayName
    }

    /// The bridged Kotlin locale this option round-trips to, for the ViewModel's `changeRegion`.
    var locale: MetadataLocale { MetadataLocale(region: region, language: language) }

    /// The markets offered in the region pickers, in display order — mapped once from
    /// `MetadataLocale.SUPPORTED` (static, off any SwiftUI diff path).
    static let all: [MetadataRegionOption] =
        MetadataLocale.Companion.shared.SUPPORTED.map(MetadataRegionOption.init)
}
