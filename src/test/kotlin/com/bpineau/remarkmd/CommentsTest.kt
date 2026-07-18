package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.BrokenFrontMatter
import com.bpineau.remarkmd.core.Comments
import com.bpineau.remarkmd.core.DocumentParser
import com.bpineau.remarkmd.core.ParsedDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ports of the addComment cases from RemarkMDTests/CommentEditingTests. */
class CommentsTest {

    private val ts = "2026-07-18T00:00:00Z"

    @Test
    fun `commenting on a plain file creates the mdreview block and anchors the marker`() {
        val doc = ParsedDocument(frontMatter = null, rawBody = "# Title\n\nhello world\n")
        val r = Comments.addComment(doc, "hello world", null, "please fix", "Bernie", ts)

        assertEquals("c1", r.commentId)
        assertNotNull(r.doc.frontMatter)
        assertEquals(1, r.doc.comments.size)
        assertTrue(r.doc.rawBody.contains("[^c1]"))
        // Section derived from the nearest heading.
        assertEquals("Title", r.doc.comments[0].section)
        assertEquals("please fix", r.doc.comments[0].thread[0].body)
    }

    @Test
    fun `an unlocatable quote is kept as an unanchored comment`() {
        val doc = ParsedDocument(frontMatter = null, rawBody = "# Title\n\nhello world\n")
        val r = Comments.addComment(doc, "not present in body", null, "note", "Bernie", ts)

        assertEquals("c1", r.commentId)
        assertEquals(1, r.doc.comments.size)
        assertFalse(r.doc.rawBody.contains("[^c1]"))
    }

    @Test
    fun `an empty body is refused`() {
        val doc = ParsedDocument(frontMatter = null, rawBody = "text")
        val r = Comments.addComment(doc, "text", null, "   ", "Bernie", ts)
        assertNull(r.commentId)
        assertEquals(doc, r.doc)
    }

    @Test
    fun `commenting is refused while the front matter is unreadable`() {
        val doc = ParsedDocument(
            frontMatter = null,
            rawBody = "text",
            brokenFrontMatter = BrokenFrontMatter("bad: [", "broken", "detail"),
        )
        val r = Comments.addComment(doc, "text", null, "note", "Bernie", ts)
        assertNull(r.commentId)
        assertEquals(doc, r.doc)
    }

    @Test
    fun `a second comment gets the next id`() {
        val doc = ParsedDocument(frontMatter = null, rawBody = "alpha beta gamma\n")
        val first = Comments.addComment(doc, "alpha", null, "one", "B", ts)
        val second = Comments.addComment(first.doc, "gamma", null, "two", "B", ts)
        assertEquals("c2", second.commentId)
        assertEquals(2, second.doc.comments.size)
        // Both markers present, and the whole thing round-trips through the parser.
        val reparsed = DocumentParser.parse(DocumentParser.serialize(second.doc))
        assertEquals(2, reparsed.comments.size)
    }
}
