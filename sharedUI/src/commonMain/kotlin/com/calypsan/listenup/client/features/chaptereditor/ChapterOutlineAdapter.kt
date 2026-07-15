package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.reorderable.ReorderMove
import com.calypsan.listenup.client.design.reorderable.ReorderNode
import com.calypsan.listenup.client.domain.chapter.groupChapters
import com.calypsan.listenup.client.domain.model.Chapter

/** Stable synthetic id for a Book-tier header node. NOT persisted — index-based, valid only within one render pass. */
private fun bookNodeId(bookIndex: Int) = "book-header-$bookIndex"

/** Stable synthetic id for a Part-tier header node. NOT persisted — index-based, valid only within one render pass. */
private fun partNodeId(
    bookIndex: Int,
    partIndex: Int,
) = "part-header-$bookIndex-$partIndex"

/**
 * Builds the nested [ReorderNode] tree the Structure lens renders and drags: one synthetic header
 * node per titled book/part group from [groupChapters] (a `null` title contributes no header node
 * at all — a fully flat, header-free chapter list produces nothing but root-level chapter leaves),
 * and one leaf node per [Chapter], nested under the innermost open header. Only header nodes set
 * [ReorderNode.canHaveChildren] — a chapter leaf is never a legal drop target for another node.
 */
internal fun List<Chapter>.toReorderNodes(): List<ReorderNode> {
    val nodes = mutableListOf<ReorderNode>()
    groupChapters().forEachIndexed { bookIndex, book ->
        val bookId = book.title?.let { bookNodeId(bookIndex) }
        if (bookId != null) {
            nodes += ReorderNode(id = bookId, parentId = null, canHaveChildren = true)
        }
        book.parts.forEachIndexed { partIndex, part ->
            val partId = part.title?.let { partNodeId(bookIndex, partIndex) }
            if (partId != null) {
                nodes += ReorderNode(id = partId, parentId = bookId, canHaveChildren = true)
            }
            val leafParentId = partId ?: bookId
            part.chapters.forEach { chapter ->
                nodes += ReorderNode(id = chapter.id, parentId = leafParentId, canHaveChildren = false)
            }
        }
    }
    return nodes
}

/**
 * Maps every header node id produced by [toReorderNodes] to the (bookTitle, partTitle) pair a
 * chapter dropped **directly** under that header should adopt — i.e. the title(s) that header's
 * group already carries. A book header's own group has no part title (dropping a chapter straight
 * onto a book header opens/rejoins that book's implicit null-titled part).
 */
private fun headerTitles(chapters: List<Chapter>): Map<String, Pair<String?, String?>> {
    val titles = mutableMapOf<String, Pair<String?, String?>>()
    chapters.groupChapters().forEachIndexed { bookIndex, book ->
        if (book.title != null) {
            titles[bookNodeId(bookIndex)] = book.title to null
        }
        book.parts.forEachIndexed { partIndex, part ->
            if (part.title != null) {
                titles[partNodeId(bookIndex, partIndex)] = book.title to part.title
            }
        }
    }
    return titles
}

/**
 * The two operations [interpretMove] can produce for the VM to apply. Pure data — this file never
 * calls the VM directly.
 */
internal sealed interface OutlineEdit {
    /** Change [chapterId]'s own [Chapter.partTitle]/[Chapter.bookTitle] — no other chapter is touched. */
    data class RelabelChapter(
        val chapterId: String,
        val partTitle: String?,
        val bookTitle: String?,
    ) : OutlineEdit

    /** Replace the full chapter ordering with [orderedChapterIds] — no chapter's labels change. */
    data class Reorder(
        val orderedChapterIds: List<String>,
    ) : OutlineEdit
}

/**
 * Interprets a [ReorderMove] against the outline [nodes] built by [toReorderNodes] for [chapters]:
 * a chapter leaf moved under a different part/book header changes ITS OWN [Chapter.partTitle] /
 * [Chapter.bookTitle] to open (or rejoin) an EXISTING header there — it never renames the target
 * header and never touches any other chapter's labels. Moving a header node itself reorders that
 * whole group's position in the draft's flat chapter ordering — a positional move, not a relabel.
 *
 * Every title returned in an [OutlineEdit.RelabelChapter] is copied verbatim from a title already
 * present on some chapter in [chapters] (or `null`) — this function never synthesizes, numbers, or
 * defaults a label string.
 */
internal fun interpretMove(
    nodes: List<ReorderNode>,
    chapters: List<Chapter>,
    move: ReorderMove,
): OutlineEdit {
    val chapterIds = chapters.map { it.id }.toSet()

    return if (move.movedId in chapterIds) {
        val (bookTitle, partTitle) = move.newParentId?.let { headerTitles(chapters)[it] } ?: (null to null)
        OutlineEdit.RelabelChapter(chapterId = move.movedId, partTitle = partTitle, bookTitle = bookTitle)
    } else {
        val reordered = applyNodeMove(nodes, move)
        OutlineEdit.Reorder(orderedChapterIds = reordered.map { it.id }.filter { it in chapterIds })
    }
}

/**
 * Applies [move] to [nodes] at the tree-structure level (reparenting [ReorderMove.movedId] among
 * [ReorderMove.newParentId]'s children at [ReorderMove.newIndex]) and returns the resulting nodes
 * in a fresh pre-order flattening. The moved node's subtree travels with it — a header's chapters
 * keep their relative order and stay nested under it.
 */
private fun applyNodeMove(
    nodes: List<ReorderNode>,
    move: ReorderMove,
): List<ReorderNode> {
    val nodesById = nodes.associateBy { it.id }
    val childrenOf = mutableMapOf<String?, MutableList<String>>()
    for (node in nodes) {
        childrenOf.getOrPut(node.parentId) { mutableListOf() } += node.id
    }

    val movedNode = nodesById.getValue(move.movedId)
    childrenOf[movedNode.parentId]?.remove(move.movedId)

    val newSiblings = childrenOf.getOrPut(move.newParentId) { mutableListOf() }
    newSiblings.add(move.newIndex.coerceIn(0, newSiblings.size), move.movedId)

    val result = mutableListOf<ReorderNode>()

    fun visit(parentId: String?) {
        for (id in childrenOf[parentId].orEmpty()) {
            val original = nodesById.getValue(id)
            val parentIdForNode = if (id == move.movedId) move.newParentId else original.parentId
            result += original.copy(parentId = parentIdForNode)
            visit(id)
        }
    }
    visit(null)
    return result
}
