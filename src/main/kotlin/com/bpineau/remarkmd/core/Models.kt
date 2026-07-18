package com.bpineau.remarkmd.core

// The document's contents, parsed by DocumentParser. Nothing here is UI state.
// Ported from RemarkMD/Model/CommentModels.swift and RemarkMD/Document/ParsedDocument.swift.

enum class CommentStatus(val yaml: String) {
    OPEN("open"),
    RESOLVED("resolved");

    companion object {
        fun from(raw: String?): CommentStatus =
            entries.firstOrNull { it.yaml == raw } ?: OPEN
    }
}

data class ThreadNote(
    val author: String,
    val date: String,
    val body: String,
)

data class Comment(
    val id: String,
    val status: CommentStatus,
    val section: String,
    val quote: String,
    val thread: List<ThreadNote>,
    // YAML keys: resolved_by / resolved_date. Omitted from output when null (mirrors Yams).
    val resolution: String? = null,
    val resolvedBy: String? = null,
    val resolvedDate: String? = null,
)

data class MdReviewFrontMatter(
    val version: Int,
    val instructions: String,
    val comments: List<Comment>,
)

/**
 * An mdreview block that is there but will not decode — one wrong key from Claude is enough.
 * Held verbatim, because the file must open anyway and saving it must not destroy whatever is
 * really in there. See [DocumentParser.parse].
 */
data class BrokenFrontMatter(
    /** Exactly the text between the `---` fences, so serialize can hand it back untouched. */
    val yaml: String,
    /** What is wrong, in the reviewer's language. A whole sentence. */
    val reason: String,
    /** The same thing for whoever will fix it: the path and the parser's own complaint. */
    val detail: String,
)

data class ParsedDocument(
    val frontMatter: MdReviewFrontMatter?,
    val rawBody: String,
    val brokenFrontMatter: BrokenFrontMatter? = null,
) {
    val comments: List<Comment> get() = frontMatter?.comments ?: emptyList()
    val openComments: List<Comment> get() = comments.filter { it.status == CommentStatus.OPEN }

    val frontMatterIsUnreadable: Boolean get() = brokenFrontMatter != null

    companion object {
        // A plain .md file has no mdreview block. Commenting on one has to create it, otherwise
        // the comment is dropped while its [^cN] marker is written into the body.
        fun emptyFrontMatter(): MdReviewFrontMatter =
            MdReviewFrontMatter(version = 1, instructions = MDREVIEW_INSTRUCTIONS, comments = emptyList())
    }
}
