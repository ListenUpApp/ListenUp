package com.calypsan.listenup.client.presentation.genredestination

/**
 * Platform-neutral visual category a facet (genre/tag/mood) name maps to. Android and iOS each
 * bind these to their own icon set — this enum only names the category.
 */
enum class FacetIcon {
    FANTASY,
    SCIFI,
    MYSTERY,
    ROMANCE,
    HORROR,
    HISTORY,
    BIOGRAPHY,
    BUSINESS,
    SCIENCE,
    SELF_HELP,
    CHILDREN,
    YOUNG_ADULT,
    HEALTH,
    FOOD,
    TRAVEL,
    POETRY,
    LITERARY,
    RELIGION,
    ART,
    MUSIC,
    COMIC,
    ANTHOLOGY,
    HUMOR,
    POLITICS,
    TECH,
    SPORT,
    DEFAULT,
}

/**
 * Derives a stable visual identity — an accent hue and an icon category — from a facet's display
 * name. Pure and deterministic: the same name always yields the same hue/icon, so genre
 * destination pages keep a consistent identity across app launches and sync refreshes, without
 * persisting any additional per-facet styling data.
 */
object FacetIdentity {
    private val PALETTE =
        listOf(
            "#2E5AA0", "#B04A66", "#8A5A20", "#1F7E74", "#6E4AA6", "#3A6A3A",
            "#C2622A", "#3F4658", "#4A5A6E", "#1F6A74", "#A6602E", "#5B3A8A",
        )

    // Order matters: the first matching pattern wins (e.g. SCIENCE before SELF_HELP, SCIFI/HISTORY
    // before LITERARY's broad "fiction" match).
    private val ICON_PATTERNS: List<Pair<FacetIcon, Regex>> =
        listOf(
            FacetIcon.FANTASY to Regex("fantasy|myth|magic|dragon"),
            FacetIcon.SCIFI to Regex("sci-?fi|science fiction|space|dystop"),
            FacetIcon.MYSTERY to Regex("mystery|thriller|crime|detective|noir"),
            FacetIcon.ROMANCE to Regex("romance|love"),
            FacetIcon.HORROR to Regex("horror|ghost|gothic|paranormal"),
            FacetIcon.HISTORY to Regex("histor|ancient|medieval|america|world war"),
            FacetIcon.BIOGRAPHY to Regex("biograph|memoir|autobiog"),
            FacetIcon.BUSINESS to Regex("business|econom|money|finance|leader"),
            FacetIcon.SCIENCE to Regex("science|physic|biolog|nature|environ"),
            FacetIcon.SELF_HELP to Regex("self|develop|improv|habit|mindful"),
            FacetIcon.CHILDREN to Regex("child|kids|juvenile|picture"),
            FacetIcon.YOUNG_ADULT to Regex("young adult|teen"),
            FacetIcon.HEALTH to Regex("health|medic|medicine|fitness|wellness"),
            FacetIcon.FOOD to Regex("food|cook|culinary|agricult|wine"),
            FacetIcon.TRAVEL to Regex("travel|adventure|outdoor"),
            FacetIcon.POETRY to Regex("poetry|poem"),
            FacetIcon.LITERARY to Regex("literary|classic|fiction"),
            FacetIcon.RELIGION to Regex("religio|spirit|faith|theolog"),
            FacetIcon.ART to Regex("art|design|photo|craft"),
            FacetIcon.MUSIC to Regex("music|audio"),
            FacetIcon.COMIC to Regex("comic|graphic|manga"),
            FacetIcon.ANTHOLOGY to Regex("antholog|short stor|collection"),
            FacetIcon.HUMOR to Regex("humor|comedy|funny"),
            FacetIcon.POLITICS to Regex("politic|social|society"),
            FacetIcon.TECH to Regex("tech|comput|program|engineer"),
            FacetIcon.SPORT to Regex("sport|game"),
        )

    /** A stable accent hue (hex string) for [name], hashed deterministically into the palette. */
    fun hue(name: String): String {
        var hash = 0u
        for (char in name) {
            hash = hash * 31u + char.code.toUInt()
        }
        return PALETTE[(hash % PALETTE.size.toUInt()).toInt()]
    }

    /** The icon category matching [name] via the first pattern that hits, else [FacetIcon.DEFAULT]. */
    fun icon(name: String): FacetIcon {
        val lowered = name.lowercase()
        return ICON_PATTERNS
            .firstOrNull { (_, pattern) -> pattern.containsMatchIn(lowered) }
            ?.first
            ?: FacetIcon.DEFAULT
    }
}
