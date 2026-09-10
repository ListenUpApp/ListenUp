package com.calypsan.listenup.client.xr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.material3.EnableXrComponentOverrides
import androidx.xr.compose.material3.ExperimentalMaterial3XrApi
import androidx.xr.compose.material3.SpaceToggleButton
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialMainPanel
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.scene

/** Throwaway: proves the alpha17 API shape compiles and that a phone is unaffected. */
@OptIn(ExperimentalMaterial3XrApi::class)
@Composable
fun XrSpikeShell(content: @Composable () -> Unit) {
    val hasXr = LocalSpatialConfiguration.current.hasXrSpatialFeature
    val spatialUi = LocalSpatialCapabilities.current.isSpatialUiEnabled
    val session = LocalSession.current
    EnableXrComponentOverrides {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (hasXr) {
                SpaceToggleButton(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
            }
            Orbiter(position = ContentEdge.Top, offset = 8.dp, alignment = Alignment.CenterHorizontally) {
                Text("spatial=$spatialUi session=${session != null}")
            }
        }
    }
    if (spatialUi) {
        Subspace {
            SpatialMainPanel(modifier = SubspaceModifier.width(1400.dp).height(900.dp).movable().resizable())
            SpatialPanel(modifier = SubspaceModifier.width(720.dp).height(120.dp).movable()) {
                Text("detached transport goes here")
            }
        }
    }
}

/** Throwaway: the space-mode request shape. */
@Composable
fun rememberStepIn(): () -> Unit {
    val session = LocalSession.current
    return { session?.scene?.requestFullSpaceMode() }
}
