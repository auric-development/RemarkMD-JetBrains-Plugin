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
}
