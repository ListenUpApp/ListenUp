package com.calypsan.listenup.web.features.contributormetadata

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.metadata.MetadataLocale
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The Audible markets a search can run against. */
@Composable
internal fun RegionSelector(
    selected: MetadataLocale,
    onRegion: (MetadataLocale) -> Unit,
) {
    Div(attrs = { classes("mdx-regions") }) {
        Span(attrs = { classes("mdx-regions-l") }) { Text("Region") }
        MetadataLocale.SUPPORTED.forEach { region ->
            Button(attrs = {
                classes("pill")
                if (region == selected) classes("on")
                attr("type", "button")
                attr("aria-pressed", (region == selected).toString())
                onClick { onRegion(region) }
            }) { Text(region.displayName) }
        }
    }
}
