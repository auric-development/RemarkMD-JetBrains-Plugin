package com.bpineau.remarkmd.core

/**
 * The comment-creation rules, pure so they can be tested and applied inside a WriteCommandAction.
 * Ported from RemarkMDDocument.addComment.
 *
 * Handles the two invariants the Mac app documents: a plain .md has no front matter, so commenting
 * on one must create it (else the comment is dropped while a `[^cN]` marker is written into the
 * body); and a comment whose quote cannot be located is kept anyway, unanchored, rather than lost.
 */
object Comments {

    data class Result(val doc: ParsedDocument, val commentId: String?)

    fun addComment(
        doc: ParsedDocument,
        quote: String,
        section: String?,
        body: String,
        author: String,
        timestamp: String,
    ): Result {
        // Refuse while the front matter is unreadable — a comment cannot be merged into YAML we
        // could not parse, and taking one only to drop it is worse than declining it.
        if (doc.frontMatterIsUnreadable) return Result(doc, null)
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Result(doc, null)

        val fm = doc.frontMatter ?: ParsedDocument.emptyFrontMatter()
        val id = DocumentParser.nextCommentId(doc)

        val anchored = DocumentParser.insertMarker(id, quote, doc.rawBody)
        val newBody = anchored ?: doc.rawBody

        val resolvedSection = when {
            !section.isNullOrEmpty() -> section
            anchored != null -> DocumentParser.section(id, newBody)
            else -> ""
        }

        val comment = Comment(
            id = id,
            status = CommentStatus.OPEN,
            section = resolvedSection,
            quote = quote,
            thread = listOf(ThreadNote(author = author, date = timestamp, body = trimmed)),
        )
        val newFm = fm.copy(comments = fm.comments + comment)
        return Result(ParsedDocument(newFm, newBody), id)
    }

    /**
     * Appends a reply to the given comment's thread. Ported from RemarkMDDocument.addReply.
     * Refuses (returns the doc unchanged) on a blank body, on unreadable front matter, or when no
     * comment with [id] exists.
     */
    fun addReply(
        doc: ParsedDocument,
        id: String,
        body: String,
        author: String,
        timestamp: String,
    ): ParsedDocument {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return doc
        return mutateComment(doc, id) { c ->
            c.copy(thread = c.thread + ThreadNote(author = author, date = timestamp, body = trimmed))
        }
    }

    /**
     * Marks the comment resolved. Ported from RemarkMDDocument.resolve. The resolution note is the
     * caller's [note] if given, else the comment's existing resolution, else "Resolved by <author>".
     */
    fun resolve(
        doc: ParsedDocument,
        id: String,
        author: String,
        note: String?,
        timestamp: String,
    ): ParsedDocument = mutateComment(doc, id) { c ->
        c.copy(
            status = CommentStatus.RESOLVED,
            resolvedBy = author,
            resolvedDate = timestamp,
            resolution = note ?: c.resolution ?: "Resolved by $author",
        )
    }

    /** Reopens the comment, clearing every resolution field. Ported from RemarkMDDocument.reopen. */
    fun reopen(doc: ParsedDocument, id: String): ParsedDocument = mutateComment(doc, id) { c ->
        c.copy(
            status = CommentStatus.OPEN,
            resolvedBy = null,
            resolvedDate = null,
            resolution = null,
        )
    }

    /**
     * Removes a comment and strips its `[^id]` marker from the body. Ported from
     * RemarkMDDocument.deleteComment. Refuses while the front matter is unreadable or when no
     * comment with [id] exists.
     */
    fun deleteComment(doc: ParsedDocument, id: String): ParsedDocument {
        if (doc.frontMatterIsUnreadable) return doc
        val fm = doc.frontMatter ?: return doc
        if (fm.comments.none { it.id == id }) return doc
        val newFm = fm.copy(comments = fm.comments.filterNot { it.id == id })
        val newBody = doc.rawBody.replace("[^$id]", "")
        return ParsedDocument(newFm, newBody)
    }

    /**
     * Covers addReply, resolve and reopen: apply [change] to the one comment with [id]. Refuses on
     * unreadable front matter or a missing comment, mirroring RemarkMDDocument.mutateComment.
     */
    private inline fun mutateComment(
        doc: ParsedDocument,
        id: String,
        change: (Comment) -> Comment,
    ): ParsedDocument {
        if (doc.frontMatterIsUnreadable) return doc
        val fm = doc.frontMatter ?: return doc
        val index = fm.comments.indexOfFirst { it.id == id }
        if (index < 0) return doc
        val newComments = fm.comments.toMutableList().apply { this[index] = change(this[index]) }
        return ParsedDocument(fm.copy(comments = newComments), doc.rawBody)
    }
}
