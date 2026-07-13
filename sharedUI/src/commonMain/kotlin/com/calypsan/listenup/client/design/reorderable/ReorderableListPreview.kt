package com.calypsan.listenup.client.design.reorderable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Visual sanity gallery for [ReorderableList] — not test-gated, reviewed by eye. Proves the two
 * shapes the primitive exists to serve render sensibly: a flat list (the Reading-Orders shape)
 * and a two-level nested outline (the chapter-Structure shape).
 */
@Preview
@Composable
private fun ReorderableListFlatPreview() {
    val nodes =
        listOf(
            ReorderNode(id = "book-1", parentId = null, canHaveChildren = false),
            ReorderNode(id = "book-2", parentId = null, canHaveChildren = false),
            ReorderNode(id = "book-3", parentId = null, canHaveChildren = false),
            ReorderNode(id = "book-4", parentId = null, canHaveChildren = false),
            ReorderNode(id = "book-5", parentId = null, canHaveChildren = false),
        )
    ReorderableList(
        nodes = nodes,
        onMove = {},
        itemContent = { nodeId -> PreviewRow(nodeId) },
    )
}

@Preview
@Composable
private fun ReorderableListNestedPreview() {
    val nodes =
        listOf(
            ReorderNode(id = "part-1", parentId = null, canHaveChildren = true),
            ReorderNode(id = "ch-1", parentId = "part-1", canHaveChildren = false),
            ReorderNode(id = "ch-2", parentId = "part-1", canHaveChildren = false),
            ReorderNode(id = "part-2", parentId = null, canHaveChildren = true),
            ReorderNode(id = "ch-3", parentId = "part-2", canHaveChildren = false),
            ReorderNode(id = "ch-4", parentId = "part-2", canHaveChildren = false),
        )
    ReorderableList(
        nodes = nodes,
        onMove = {},
        itemContent = { nodeId ->
            val isHeader = nodeId.startsWith("part-")
            PreviewRow(nodeId, indented = !isHeader)
        },
    )
}

@Composable
private fun PreviewRow(
    label: String,
    indented: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = if (indented) 24.dp else 8.dp, end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
