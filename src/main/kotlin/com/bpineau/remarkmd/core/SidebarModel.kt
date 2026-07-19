package com.bpineau.remarkmd.core

/**
 * The pure shaping of a [ParsedDocument] into the cards the comments sidebar draws — the port of
 * the filtering and per-comment derivation that `SidebarView`/`CommentCard` do inline in the Mac
 * app. Kept pure (and tested) so the one thing that is easy to get wrong — hiding resolved comments
 * by default, and flagging a comment whose `[^cN]` marker has gone missing — cannot silently break.
 */
data class ThreadLine(val author: String, val date: String, val body: String)

data class CommentCardModel(
    val id: String,
    val section: String,
    val quote: String,
    val status: CommentStatus,
    val thread: List<ThreadLine>,
    /** The resolution note, only when the comment is resolved. */
    val resolution: String?,
    /** True when no `[^id]` marker survives in the prose — the context it anchored to has changed. */
    val markerMissing: Boolean,
)

object SidebarModel {

    /**
     * Cards for the visible comments. Resolved comments are hidden unless [showResolved]; order and
     * everything else mirrors `SidebarView.visibleComments` + `CommentCard`.
     */
    fun buildCards(doc: ParsedDocument, showResolved: Boolean): List<CommentCardModel> =
        doc.comments
            .filter { showResolved || it.status == CommentStatus.OPEN }
            .map { c ->
                CommentCardModel(
                    id = c.id,
                    section = c.section,
                    quote = c.quote,
                    status = c.status,
                    thread = c.thread.map { ThreadLine(it.author, it.date, it.body) },
                    resolution = if (c.status == CommentStatus.RESOLVED) c.resolution else null,
                    markerMissing = !DocumentParser.markerExists(c.id, doc.rawBody),
                )
            }
}
