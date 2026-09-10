package com.calypsan.listenup.web.features.contributormetadata

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorSearchLoadState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import com.calypsan.listenup.web.features.contributoredit.contributorPhotoUrl
import com.calypsan.listenup.web.features.metadata.RegionSelector
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Match a contributor — find this person on Audible and take their biography and photo.
 *
 * Pure in [state]; the store wiring lives one level up. Two phases, as the book wizard has: find
 * the person, then look at what would change.
 *
 * ⛔ **Apply changes the biography and the photo. It does not rename anybody.** The server's applier
 * writes exactly `asin`, `description` and `imagePath`, and never blanks an existing value with a
 * missing incoming one. So the matched name is shown as *identification* — which person this is —
 * rather than as a before-and-after, because a before-and-after would promise a rename that never
 * happens.
 */
@Composable
fun ContributorMetadataPage(
    state: ContributorMetadataUiState,
    onQuery: (String) -> Unit,
    onRegion: (MetadataLocale) -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (MetadataContributorHit) -> Unit,
    onClearSelection: () -> Unit,
    onApply: () -> Unit,
    onLeave: () -> Unit,
) {
    Div(attrs = { classes("cmx") }) {
        Div(attrs = { classes("cmx-head") }) {
            H1(attrs = { classes("cmx-t") }) { Text("Match contributor") }
            Button(attrs = {
                classes(BTN_SECONDARY)
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onLeave() }
            }) { Text("Back") }
        }

        when (state) {
            is ContributorMetadataUiState.Idle -> {
                Div(attrs = { classes("skel", "cmx-skel") })
            }

            is ContributorMetadataUiState.Search -> {
                SearchPhase(state, onQuery, onRegion, onSearch, onSelectCandidate)
            }

            is ContributorMetadataUiState.Preview -> {
                PreviewPhase(state, onRegion, onClearSelection, onApply)
            }
        }
    }
}

@Composable
private fun SearchPhase(
    state: ContributorMetadataUiState.Search,
    onQuery: (String) -> Unit,
    onRegion: (MetadataLocale) -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (MetadataContributorHit) -> Unit,
) {
    state.context.current?.let { current ->
        Div(attrs = { classes("cmx-ctx") }) {
            Span(attrs = { classes("cmx-ctx-l") }) { Text("Matching") }
            Span(attrs = { classes("cmx-ctx-n") }) { Text(current.name) }
        }
    }

    Form(attrs = {
        classes("cmx-search")
        onSubmit { event ->
            event.preventDefault()
            onSearch()
        }
    }) {
        Field(
            label = "Search Audible",
            value = state.query,
            onInput = onQuery,
            leading = WebIcon.Search,
            placeholder = "Contributor name…",
            id = "cmx-query",
        )
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, "submit")
            disabledWhen(state.loadState is ContributorSearchLoadState.InFlight || state.query.isBlank())
        }) { Text(if (state.loadState is ContributorSearchLoadState.InFlight) "Searching…" else "Search Audible") }
    }

    RegionSelector(state.region, onRegion)

    when (val load = state.loadState) {
        ContributorSearchLoadState.Idle -> {
            P(attrs = { classes(NONE) }) { Text("Enter a name to search.") }
        }

        ContributorSearchLoadState.InFlight -> {
            Div(attrs = { classes("skel", "cmx-skel") })
        }

        is ContributorSearchLoadState.Failed -> {
            Alert(load.message)
        }

        is ContributorSearchLoadState.Loaded -> {
            if (load.results.isEmpty()) {
                P(attrs = { classes(NONE) }) { Text("No contributors match that search.") }
            } else {
                Div(attrs = {
                    classes("cmx-hits")
                    attr("role", "list")
                }) {
                    load.results.forEach { hit ->
                        Button(attrs = {
                            classes("cmx-hit")
                            attr(ATTR_TYPE, VALUE_BUTTON)
                            onClick { onSelectCandidate(hit) }
                        }) {
                            Span(attrs = { classes("cmx-hit-n") }) { Text(hit.name) }
                            Span(attrs = { classes("cmx-asin") }) { Text(hit.asin) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPhase(
    state: ContributorMetadataUiState.Preview,
    onRegion: (MetadataLocale) -> Unit,
    onClearSelection: () -> Unit,
    onApply: () -> Unit,
) {
    when (val load = state.loadState) {
        ContributorPreviewLoadState.Loading -> {
            Div(attrs = { classes("skel", "cmx-skel") })
        }

        is ContributorPreviewLoadState.Failed -> {
            Alert(load.message)
            ChangeMatch(onClearSelection)
        }

        // ⛔ An honest miss, not a blank preview above a live Apply. Audnexus answers a
        // cross-region fetch with an empty HTTP-200 shell, and the server refuses to apply one —
        // so the page says which catalogue was empty and offers the region switch that fixes it.
        ContributorPreviewLoadState.Missing -> {
            Div(attrs = { classes("cmx-empty") }) {
                H2 { Text("No profile in this catalog") }
                P { Text("No profile data in the ${state.region.displayName} catalog. Try a different region:") }
            }
            RegionSelector(state.region, onRegion)
            ChangeMatch(onClearSelection)
        }

        is ContributorPreviewLoadState.Ready -> {
            ReadyPreview(
                profile = load.profile,
                current = state.context.current,
                isApplying = load.isApplying,
                applyError = load.applyError,
                region = state.region,
                onRegion = onRegion,
                onClearSelection = onClearSelection,
                onApply = onApply,
            )
        }
    }
}

@Composable
private fun ReadyPreview(
    profile: MetadataContributorProfile,
    current: Contributor?,
    isApplying: Boolean,
    applyError: String?,
    region: MetadataLocale,
    onRegion: (MetadataLocale) -> Unit,
    onClearSelection: () -> Unit,
    onApply: () -> Unit,
) {
    // Identification, not a before-and-after: this names WHICH person was matched. Apply does not
    // rename anyone — see the page's own note.
    Div(attrs = { classes("cmx-who") }) {
        Span(attrs = { classes("cmx-who-n") }) { Text(profile.name) }
        Span(attrs = { classes("cmx-src") }) { Text("Audible · ${region.displayName}") }
    }

    RegionSelector(region, onRegion)

    PhotoCompare(current, profile)
    BioCompare(current?.descriptionText, profile.descriptionText)

    applyError?.let { Alert(it) }

    Div(attrs = { classes("cmx-apply") }) {
        ChangeMatch(onClearSelection)
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, VALUE_BUTTON)
            disabledWhen(isApplying)
            onClick { onApply() }
        }) {
            Icon(WebIcon.Check, size = SMALL_ICON)
            Text(if (isApplying) "Applying…" else "Apply")
        }
    }
}

/**
 * The photo, before and after.
 *
 * ⛔ A missing incoming photo keeps the current one — the server never blanks a field with a
 * missing value — so the "after" side says that rather than showing an empty frame.
 */
@Composable
private fun PhotoCompare(
    current: Contributor?,
    profile: MetadataContributorProfile,
) {
    Div(attrs = { classes("cmx-cmp") }) {
        Span(attrs = { classes("cmx-cmp-l") }) { Text("Photo") }
        Div(attrs = { classes("cmx-photos") }) {
            Div(attrs = { classes("cmx-photo") }) {
                Span(attrs = { classes(SIDE) }) { Text("Current") }
                if (current?.imagePath != null) {
                    Img(src = contributorPhotoUrl(current.idString), alt = "", attrs = { classes(PHOTO) })
                } else {
                    Div(
                        attrs = { classes(PHOTO, "cmx-photo-none") },
                    ) { Icon(WebIcon.Person, size = PHOTO_ICON) }
                }
            }
            Div(attrs = { classes("cmx-photo") }) {
                Span(attrs = { classes(SIDE, "on") }) { Text("Audible") }
                val incoming = profile.imageUrl
                if (incoming != null) {
                    Img(src = incoming, alt = "", attrs = {
                        classes(PHOTO)
                        attr("referrerpolicy", "no-referrer")
                    })
                } else {
                    Div(attrs = { classes(PHOTO, "cmx-photo-none") }) { Span { Text("Unchanged") } }
                }
            }
        }
    }
}

/** The biography, before and after. Same rule: a missing incoming bio keeps the current one. */
@Composable
private fun BioCompare(
    current: String?,
    incoming: String?,
) {
    Div(attrs = { classes("cmx-cmp") }) {
        Span(attrs = { classes("cmx-cmp-l") }) { Text("Biography") }
        Div(attrs = { classes("cmx-side-v") }) {
            Span(attrs = { classes(SIDE) }) { Text("Current") }
            P(attrs = { classes("cmx-bio") }) { Text(current?.takeIf { it.isNotBlank() } ?: EMPTY_VALUE) }
        }
        Div(attrs = { classes("cmx-side-v") }) {
            Span(attrs = { classes(SIDE, "on") }) { Text("Audible") }
            P(attrs = { classes("cmx-bio") }) {
                Text(incoming?.takeIf { it.isNotBlank() } ?: "Unchanged — Audible has no biography.")
            }
        }
    }
}

@Composable
private fun ChangeMatch(onClearSelection: () -> Unit) {
    Button(attrs = {
        classes(BTN_SECONDARY)
        attr(ATTR_TYPE, VALUE_BUTTON)
        onClick { onClearSelection() }
    }) { Text("Change match") }
}

@Composable
private fun Alert(message: String) {
    P(attrs = {
        classes("cmx-err")
        attr("role", "alert")
    }) { Text(message) }
}

/** What an absent value reads as — an em dash, not an empty line that looks like a bug. */
private const val EMPTY_VALUE = "—"

private const val ATTR_TYPE = "type"

private const val BTN_SECONDARY = "btn-o"

private const val VALUE_BUTTON = "button"

private const val NONE = "cmx-none"

private const val SMALL_ICON = 16

private const val PHOTO = "cmx-photo-i"

private const val SIDE = "cmx-side"

private const val PHOTO_ICON = 28
