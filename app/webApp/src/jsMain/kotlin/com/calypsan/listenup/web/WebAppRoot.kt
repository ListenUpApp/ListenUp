package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.WebAppSurface
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text

/**
 * The root of the ListenUp web body.
 *
 * The voice and density contract now lives in
 * [com.calypsan.listenup.web.design.WebAppSurface] rather than here — it is a property of every
 * surface, not of this one screen.
 *
 * Deliberately thin for now. Real structure arrives with the shell and Book Detail; this exists
 * so the rendering path stays proven while the kit is built beneath it.
 */
@Composable
fun WebAppRoot() {
    WebAppSurface {
        H1 { Text("ListenUp") }
    }
}
