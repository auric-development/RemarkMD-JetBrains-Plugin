package com.bpineau.remarkmd.editor

import com.bpineau.remarkmd.core.Comments
import com.bpineau.remarkmd.core.DocumentParser
import com.bpineau.remarkmd.core.ParsedDocument
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The platform glue for the sidebar's per-card mutations. Every one follows the same discipline the
 * whole plugin uses: parse the live Document text, run a PURE `Comments` function, serialize, and
 * `setText` — all inside a [WriteCommandAction], which is what buys undo, the dirty flag and save.
 * The read preview re-renders on its own because it listens to the same Document.
 *
 * Nothing here decides *whether* a mutation is legal — the `Comments` functions already refuse a
 * blank reply, a missing comment, or unreadable front matter, returning the document unchanged, and
 * `setText` is skipped when the text did not move.
 */
object CommentActions {

    private fun author(): String =
        System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "Reviewer"

    private fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    fun reply(project: Project, document: Document, id: String, body: String) =
        mutate(project, document, "Reply to Comment") { Comments.addReply(it, id, body, author(), now()) }

    fun resolve(project: Project, document: Document, id: String, note: String?) =
        mutate(project, document, "Resolve Comment") { Comments.resolve(it, id, author(), note, now()) }

    fun reopen(project: Project, document: Document, id: String) =
        mutate(project, document, "Reopen Comment") { Comments.reopen(it, id) }

    fun delete(project: Project, document: Document, id: String) =
        mutate(project, document, "Delete Comment") { Comments.deleteComment(it, id) }

    private fun mutate(
        project: Project,
        document: Document,
        name: String,
        transform: (ParsedDocument) -> ParsedDocument,
    ) {
        WriteCommandAction.runWriteCommandAction(project, name, null, Runnable {
            val updated = transform(DocumentParser.parse(document.text))
            val text = DocumentParser.serialize(updated)
            if (text != document.text) document.setText(text)
        })
    }
}
