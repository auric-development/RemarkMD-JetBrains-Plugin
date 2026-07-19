package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.BrokenFrontMatter
import com.bpineau.remarkmd.core.Comments
import com.bpineau.remarkmd.core.CommentStatus
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

    // A single-comment document to exercise reply/resolve/reopen/delete against.
    private fun seeded(): ParsedDocument {
        val doc = ParsedDocument(frontMatter = null, rawBody = "# Title\n\nhello world\n")
        return Comments.addComment(doc, "hello world", null, "please fix", "Bernie", ts).doc
    }

    private val broken = ParsedDocument(
        frontMatter = null,
        rawBody = "text",
        brokenFrontMatter = BrokenFrontMatter("bad: [", "broken", "detail"),
    )

    @Test
    fun `a reply is appended to the thread`() {
        val d = Comments.addReply(seeded(), "c1", "on it", "Claude", ts)
        assertEquals(2, d.comments[0].thread.size)
        assertEquals("Claude", d.comments[0].thread[1].author)
        assertEquals("on it", d.comments[0].thread[1].body)
        assertEquals(ts, d.comments[0].thread[1].date)
    }

    @Test
    fun `a blank reply is refused`() {
        val seed = seeded()
        assertEquals(seed, Comments.addReply(seed, "c1", "   ", "Claude", ts))
    }

    @Test
    fun `a reply to a missing comment is refused`() {
        val seed = seeded()
        assertEquals(seed, Comments.addReply(seed, "c99", "note", "Claude", ts))
    }

    @Test
    fun `resolve sets the snake_case fields and round-trips through the parser`() {
        val d = Comments.resolve(seeded(), "c1", "Claude", "fixed the typo", ts)
        val c = d.comments[0]
        assertEquals(CommentStatus.RESOLVED, c.status)
        assertEquals("Claude", c.resolvedBy)
        assertEquals(ts, c.resolvedDate)
        assertEquals("fixed the typo", c.resolution)

        val text = DocumentParser.serialize(d)
        assertTrue(text.contains("resolved_by: Claude"))
        assertTrue(text.contains("resolved_date:"))
        val reparsed = DocumentParser.parse(text).comments[0]
        assertEquals(CommentStatus.RESOLVED, reparsed.status)
        assertEquals("Claude", reparsed.resolvedBy)
        assertEquals(ts, reparsed.resolvedDate)
        assertEquals("fixed the typo", reparsed.resolution)
    }

    @Test
    fun `resolve with no note falls back to a default`() {
        val d = Comments.resolve(seeded(), "c1", "Claude", null, ts)
        assertEquals("Resolved by Claude", d.comments[0].resolution)
    }

    @Test
    fun `resolve with no note keeps an existing resolution`() {
        val once = Comments.resolve(seeded(), "c1", "Claude", "the real note", ts)
        val reopened = Comments.reopen(once, "c1")
        // resolution was cleared by reopen; set it back to simulate a pre-existing note.
        val withNote = Comments.resolve(reopened, "c1", "Claude", "the real note", ts)
        val again = Comments.resolve(withNote, "c1", "Someone", null, ts)
        assertEquals("the real note", again.comments[0].resolution)
    }

    @Test
    fun `reopen clears every resolution field`() {
        val resolved = Comments.resolve(seeded(), "c1", "Claude", "note", ts)
        val d = Comments.reopen(resolved, "c1")
        val c = d.comments[0]
        assertEquals(CommentStatus.OPEN, c.status)
        assertNull(c.resolvedBy)
        assertNull(c.resolvedDate)
        assertNull(c.resolution)
    }

    @Test
    fun `delete removes the comment and strips its marker`() {
        val seed = seeded()
        assertTrue(seed.rawBody.contains("[^c1]"))
        val d = Comments.deleteComment(seed, "c1")
        assertTrue(d.comments.isEmpty())
        assertFalse(d.rawBody.contains("[^c1]"))
    }

    @Test
    fun `delete of a missing comment is refused`() {
        val seed = seeded()
        assertEquals(seed, Comments.deleteComment(seed, "c99"))
    }

    @Test
    fun `every mutation refuses while the front matter is unreadable`() {
        assertEquals(broken, Comments.addReply(broken, "c1", "note", "Claude", ts))
        assertEquals(broken, Comments.resolve(broken, "c1", "Claude", "note", ts))
        assertEquals(broken, Comments.reopen(broken, "c1"))
        assertEquals(broken, Comments.deleteComment(broken, "c1"))
    }
}
