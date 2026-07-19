package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.ClaudeExport
import com.bpineau.remarkmd.core.Comment
import com.bpineau.remarkmd.core.CommentStatus
import com.bpineau.remarkmd.core.MDREVIEW_INSTRUCTIONS
import com.bpineau.remarkmd.core.MdReviewFrontMatter
import com.bpineau.remarkmd.core.ParsedDocument
import com.bpineau.remarkmd.core.ThreadNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ports of the ⌘⇧K payload cases from the Swift `ClaudeExport`. */
class ClaudeExportTest {

    private fun comment(
        id: String,
        status: CommentStatus = CommentStatus.OPEN,
        section: String = "Title",
        quote: String = "hello world",
        thread: List<ThreadNote> = listOf(ThreadNote("Bernie", "2026-07-18T00:00:00Z", "please fix")),
    ) = Comment(id = id, status = status, section = section, quote = quote, thread = thread)

    private fun doc(
        comments: List<Comment>,
        instructions: String = MDREVIEW_INSTRUCTIONS,
    ) = ParsedDocument(
        frontMatter = MdReviewFrontMatter(version = 1, instructions = instructions, comments = comments),
        rawBody = "# Title\n\nhello world [^c1]\n",
    )

    @Test
    fun `returns null when there are no open comments`() {
        assertNull(ClaudeExport.openComments(ParsedDocument(frontMatter = null, rawBody = "# Title\n"), "/x.md"))
        assertNull(ClaudeExport.openComments(doc(listOf(comment("c1", status = CommentStatus.RESOLVED))), "/x.md"))
    }

    @Test
    fun `with a file path emits the File line first`() {
        val out = ClaudeExport.openComments(doc(listOf(comment("c1"))), "/Users/b/notes.md")!!
        assertTrue(out.startsWith("File: /Users/b/notes.md\n"))
    }

    @Test
    fun `without a file path there is no File line`() {
        val out = ClaudeExport.openComments(doc(listOf(comment("c1"))), null)!!
        assertFalse(out.contains("File:"))
        // Payload begins with the count line when there is no path.
        assertTrue(out.startsWith("1 open review comment "))
    }

    @Test
    fun `singular noun for one open comment`() {
        val out = ClaudeExport.openComments(doc(listOf(comment("c1"))), null)!!
        assertTrue(out.contains("1 open review comment on the Markdown document above."))
    }

    @Test
    fun `plural noun for several open comments`() {
        val out = ClaudeExport.openComments(doc(listOf(comment("c1"), comment("c2"))), null)!!
        assertTrue(out.contains("2 open review comments on the Markdown document above."))
    }

    @Test
    fun `resolved comments are excluded from the count and the body`() {
        val out = ClaudeExport.openComments(
            doc(listOf(comment("c1"), comment("c2", status = CommentStatus.RESOLVED))),
            null,
        )!!
        assertTrue(out.contains("1 open review comment on the Markdown document above."))
        assertTrue(out.contains("[^c1]"))
        assertFalse(out.contains("[^c2]"))
    }

    @Test
    fun `header carries id, section and quote, then indented author-body lines`() {
        val out = ClaudeExport.openComments(doc(listOf(comment("c1"))), null)!!
        assertTrue(out.contains("[^c1] (Title) re: \"hello world\""))
        assertTrue(out.contains("  Bernie: please fix"))
    }

    @Test
    fun `header omits empty section and empty quote`() {
        val out = ClaudeExport.openComments(
            doc(listOf(comment("c1", section = "", quote = ""))),
            null,
        )!!
        assertTrue(out.contains("\n[^c1]\n"))
        assertFalse(out.contains("[^c1] ("))
        assertFalse(out.contains("re:"))
    }

    @Test
    fun `every note in a thread is emitted`() {
        val thread = listOf(
            ThreadNote("Bernie", "t", "first"),
            ThreadNote("Claude", "t", "second"),
        )
        val out = ClaudeExport.openComments(doc(listOf(comment("c1", thread = thread))), null)!!
        assertTrue(out.contains("  Bernie: first"))
        assertTrue(out.contains("  Claude: second"))
    }

    @Test
    fun `uses the document's own instructions string`() {
        val out = ClaudeExport.openComments(
            doc(listOf(comment("c1")), instructions = "CUSTOM PROMPT FROM THE FILE"),
            null,
        )!!
        assertTrue(out.contains("CUSTOM PROMPT FROM THE FILE"))
        assertFalse(out.contains(MDREVIEW_INSTRUCTIONS))
    }

    @Test
    fun `payload is trimmed of surrounding whitespace`() {
        val out = ClaudeExport.openComments(doc(listOf(comment("c1"))), "/x.md")!!
        assertEquals(out, out.trim())
    }
}
