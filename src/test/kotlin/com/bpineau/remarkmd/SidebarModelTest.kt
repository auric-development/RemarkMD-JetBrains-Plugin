package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.Comments
import com.bpineau.remarkmd.core.CommentStatus
import com.bpineau.remarkmd.core.ParsedDocument
import com.bpineau.remarkmd.core.SidebarModel
import com.bpineau.remarkmd.ui.CommentCardHtml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure view-model shaping for the comments sidebar. */
class SidebarModelTest {

    private val ts = "2026-07-18T00:00:00Z"

    private fun docWithOneOpenOneResolved(): ParsedDocument {
        val base = ParsedDocument(frontMatter = null, rawBody = "# Title\n\nalpha beta gamma delta\n")
        val a = Comments.addComment(base, "alpha", null, "first", "Bernie", ts).doc
        val b = Comments.addComment(a, "gamma", null, "second", "Bernie", ts).doc
        // Resolve the second (c2).
        return Comments.resolve(b, "c2", "Claude", "done", ts)
    }

    @Test
    fun `resolved comments are hidden by default and shown when asked`() {
        val doc = docWithOneOpenOneResolved()

        val hidden = SidebarModel.buildCards(doc, showResolved = false)
        assertEquals(1, hidden.size)
        assertEquals("c1", hidden[0].id)
        assertEquals(CommentStatus.OPEN, hidden[0].status)

        val shown = SidebarModel.buildCards(doc, showResolved = true)
        assertEquals(2, shown.size)
        assertEquals(listOf("c1", "c2"), shown.map { it.id })
    }

    @Test
    fun `a card carries section, quote, thread and the resolution only when resolved`() {
        val doc = docWithOneOpenOneResolved()
        val cards = SidebarModel.buildCards(doc, showResolved = true).associateBy { it.id }

        val open = cards.getValue("c1")
        assertEquals("Title", open.section)
        assertEquals("alpha", open.quote)
        assertEquals("first", open.thread.single().body)
        assertEquals("Bernie", open.thread.single().author)
        assertEquals(null, open.resolution)

        val resolved = cards.getValue("c2")
        assertEquals(CommentStatus.RESOLVED, resolved.status)
        assertEquals("done", resolved.resolution)
    }

    @Test
    fun `markerMissing flags a comment whose marker was stripped from the body`() {
        val doc = docWithOneOpenOneResolved()
        // Remove c1's marker from the prose without touching the front matter.
        val mangled = doc.copy(rawBody = doc.rawBody.replace("[^c1]", ""))
        val cards = SidebarModel.buildCards(mangled, showResolved = true).associateBy { it.id }
        assertTrue(cards.getValue("c1").markerMissing)
        assertFalse(cards.getValue("c2").markerMissing)
    }

    @Test
    fun `card html escapes user text and is wrapped in html body`() {
        val doc = ParsedDocument(frontMatter = null, rawBody = "some <b>bold</b> text here\n")
        val added = Comments.addComment(
            doc, "some <b>bold</b> text here", null, "look: a & b < c", "Rev", ts,
        ).doc
        val card = SidebarModel.buildCards(added, showResolved = true).single()
        val html = CommentCardHtml.render(card)

        assertTrue(html.startsWith("<html>"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&lt;"))
        // The raw ampersand/angle from the body must not survive unescaped in the thread text.
        assertFalse(html.contains("a & b"))
    }
}
