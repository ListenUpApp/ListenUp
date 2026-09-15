package com.calypsan.listenup.web.features.licences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Field
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Open-source acknowledgements for the web client.
 *
 * ⛔ Reads `/licences.json`, which is generated from **this module's** dependency graph — the
 * Kotlin/JS libraries plus the npm packages the bundle resolves. It is deliberately not the
 * natives' `aboutlibraries.json`: that one describes the Android graph (Media3, Firebase, Play
 * Services, bytedeco), none of which a browser loads. An attribution page naming libraries the
 * reader is not running is worse than no page, so the two manifests stay separate and
 * `verifyWebLicences` keeps this one honest.
 *
 * Pure in [state]: the fetch lives one level up, in [graphLicences].
 */
@Composable
fun LicencesPage(
    state: LicencesUiState,
    onOpenSettings: () -> Unit,
) {
    Div(attrs = { classes("lic") }) {
        Breadcrumb(trail = listOf("Settings", "Open Source"), onNavigate = { onOpenSettings() })

        when (state) {
            LicencesUiState.Loading -> {
                Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
            }

            is LicencesUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("The licences can't be shown") }
                    P { Text(state.message) }
                }
            }

            is LicencesUiState.Ready -> {
                ReadyLicences(state)
            }
        }
    }
}

@Composable
private fun ReadyLicences(state: LicencesUiState.Ready) {
    var query by remember { mutableStateOf("") }
    val families =
        state.libraries
            .flatMap { it.licenses }
            .distinct()
            .size

    Div(attrs = { classes("lic-head") }) {
        Span(attrs = { classes("lic-overline") }) { Text("Open Source") }
        H1(attrs = { classes("lic-title") }) { Text("${state.libraries.size} libraries") }
        Div(attrs = { classes("lic-sub") }) { Text("that make ListenUp possible, across $families license families") }
    }

    Field(
        label = "Search libraries",
        value = query,
        onInput = { query = it },
        placeholder = "Search libraries",
        id = "lic-search",
    )

    val shown = state.libraries.filter { it.matches(query) }
    if (shown.isEmpty()) {
        Div(attrs = { classes("empty") }) { P { Text("No libraries match that.") } }
        return
    }

    Div(attrs = { classes("lic-list") }) {
        shown.forEach { library ->
            Div(attrs = { classes("lic-row") }) {
                Div(attrs = { classes("lic-row-top") }) {
                    Span(attrs = { classes("lic-name") }) { Text(library.name) }
                    // Absent rather than "v?" — a version this manifest does not carry is a fact
                    // about the manifest, not something to render a placeholder for.
                    library.artifactVersion?.let { Span(attrs = { classes("lic-ver") }) { Text("v$it") } }
                }
                library.licenses.forEach { licence ->
                    Span(attrs = { classes("lic-badge") }) { Text(licence) }
                }
                library.website?.let { url ->
                    A(href = url, attrs = {
                        classes("lic-link")
                        attr("target", "_blank")
                        attr("rel", "noopener noreferrer")
                    }) { Text("View project") }
                }
            }
        }
    }

    P(attrs = { classes("lic-footer") }) {
        Text("ListenUp is built with open-source software. Thank you to everyone who contributed.")
    }
}

/** Matches on name and licence, so "Apache" narrows to a family and "ktor" to a project. */
private fun LicencedLibrary.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return name.lowercase().contains(needle) || licenses.any { it.lowercase().contains(needle) }
}
