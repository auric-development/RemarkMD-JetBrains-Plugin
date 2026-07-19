package com.bpineau.remarkmd.core

/**
 * The handoff to Claude. This text is what the reviewer pastes into a chat, so it has to carry
 * everything needed to act on the comments and write the results back into the file: the path, the
 * `[^cN]` ids that key each comment to its marker and its front-matter entry, and the resolution
 * protocol (the `instructions` string). Dropping the ids leaves Claude unable to mark anything
 * resolved.
 *
 * Pure, and the single formatter — reached from both the action and (if present) the sidebar button,
 * exactly as the Swift `ClaudeExport` is reached from the menu command and the sidebar.
 * Ported verbatim from RemarkMD/Document/ClaudeExport.swift.
 */
object ClaudeExport {

    /** The ⌘⇧K payload, or null when there is nothing open to hand off. */
    fun openComments(doc: ParsedDocument, filePath: String?): String? {
        val open = doc.openComments
        if (open.isEmpty()) return null

        val out = mutableListOf<String>()
        if (filePath != null) {
            out.add("File: $filePath")
            out.add("")
        }

        val noun = if (open.size == 1) "comment" else "comments"
        out.add("${open.size} open review $noun on the Markdown document above.")
        out.add("Each is keyed by id to a [^cN] marker in the document body.")
        out.add("")
        out.add(doc.frontMatter?.instructions ?: MDREVIEW_INSTRUCTIONS)
        out.add("")

        for (comment in open) {
            out.add(header(comment))
            for (note in comment.thread) {
                out.add("  ${note.author}: ${note.body}")
            }
            out.add("")
        }

        return out.joinToString("\n").trim()
    }

    private fun header(comment: Comment): String {
        var line = "[^${comment.id}]"
        if (comment.section.isNotEmpty()) line += " (${comment.section})"
        if (comment.quote.isNotEmpty()) line += " re: \"${comment.quote}\""
        return line
    }
}
