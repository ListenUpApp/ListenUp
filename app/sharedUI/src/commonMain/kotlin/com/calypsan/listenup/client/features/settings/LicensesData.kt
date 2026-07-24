package com.calypsan.listenup.client.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
 * Neutral fallback color for SPDX identifiers not present in [LICENSE_FAMILY_COLORS].
 * Rendered as a muted gray that reads clearly on both light and dark surfaces.
 */
val LICENSE_FALLBACK_COLOR = Color(0xFF8A8A8E)

/**
 * Stable color palette keyed by SPDX identifier.
 * Colors are chosen to be visually distinct and accessible; they do not change between
 * recompositions (they are top-level constants).
 */
val LICENSE_FAMILY_COLORS: Map<String, Color> =
    mapOf(
        "Apache-2.0" to Color(0xFF2A6FDB),
        "MIT" to Color(0xFF1F8A5B),
        "BSD-3-Clause" to Color(0xFF7A5AF8),
        "BSD-2-Clause" to Color(0xFF5A7AF8),
        "ISC" to Color(0xFF1F8A8A),
        "LGPL-2.1-or-later" to Color(0xFFC2562A),
        "GPL-3.0" to Color(0xFFC23A3A),
        "OFL-1.1" to Color(0xFFC2389B),
    )

/**
 * Returns the display [Color] for the given SPDX identifier.
 * Falls back to [LICENSE_FALLBACK_COLOR] for unrecognised identifiers.
 */
fun licenseFamilyColor(spdxId: String): Color = LICENSE_FAMILY_COLORS[spdxId] ?: LICENSE_FALLBACK_COLOR

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
