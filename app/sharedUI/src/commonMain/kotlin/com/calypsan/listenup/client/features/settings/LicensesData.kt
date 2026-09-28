package com.calypsan.listenup.client.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.design.theme.CategoryColor
import com.calypsan.listenup.client.design.theme.CategoryPalette
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import listenup.composeapp.generated.resources.Res

/** A single row in the Licenses screen, derived from AboutLibraries metadata. */
data class LicenseRow(
    val uniqueId: String,
    val name: String,
    val version: String,
    val spdxId: String,
    val licenseText: String?,
    val url: String? = null,
)

/**
 * Neutral fallback colour for SPDX identifiers not present in [LICENSE_FAMILY_COLORS].
 */
val LICENSE_FALLBACK_COLOR: CategoryColor = CategoryPalette.Neutral

/**
 * Stable category colour keyed by SPDX identifier, drawn from [CategoryPalette] so each family
 * has a composed tone in both light and dark. The chip also carries the identifier as text, so
 * colour is never the only cue.
 */
val LICENSE_FAMILY_COLORS: Map<String, CategoryColor> =
    mapOf(
        "Apache-2.0" to CategoryPalette.Blue,
        "MIT" to CategoryPalette.Green,
        "BSD-3-Clause" to CategoryPalette.Violet,
        "BSD-2-Clause" to CategoryPalette.Periwinkle,
        "ISC" to CategoryPalette.Teal,
        "LGPL-2.1-or-later" to CategoryPalette.Orange,
        "GPL-3.0" to CategoryPalette.Red,
        "OFL-1.1" to CategoryPalette.Magenta,
    )

/**
 * Returns the [CategoryColor] for the given SPDX identifier.
 * Falls back to [LICENSE_FALLBACK_COLOR] for unrecognised identifiers.
 */
fun licenseFamilyColor(spdxId: String): CategoryColor = LICENSE_FAMILY_COLORS[spdxId] ?: LICENSE_FALLBACK_COLOR

/**
 * Loads the bundled `aboutlibraries.json` from Compose resources and maps each
 * [com.mikepenz.aboutlibraries.entity.Library] to a [LicenseRow].
 *
 * The resulting list is sorted alphabetically by library name (case-insensitive) and
 * exposed as a [State] so it integrates naturally with `collectAsStateWithLifecycle`.
 */
@Composable
fun rememberLicenseRows(): State<List<LicenseRow>> {
    val libs by produceLibraries {
        Res.readBytes("files/aboutlibraries.json").decodeToString()
    }

    return remember(libs) {
        derivedStateOf {
            libs
                ?.libraries
                .orEmpty()
                .map { library ->
                    val firstLicense = library.licenses.firstOrNull()
                    LicenseRow(
                        uniqueId = library.uniqueId,
                        name = library.name,
                        version = library.artifactVersion.orEmpty(),
                        spdxId = firstLicense?.spdxId ?: firstLicense?.name.orEmpty(),
                        licenseText = firstLicense?.licenseContent,
                        url = firstLicense?.url,
                    )
                }.sortedBy { it.name.lowercase() }
        }
    }
}
