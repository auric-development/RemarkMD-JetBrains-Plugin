package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.CommentStatus
import com.bpineau.remarkmd.core.DocumentParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ports of RemarkMDTests/RoundTripTests (the pure parser/model suite). */
class DocumentParserTest {

    private val withComment = """
        ---
        mdreview:
          version: 1
          instructions: Comments are keyed by id.
          comments:
          - id: c1
            status: open
            section: Intro
            quote: hello world
            thread:
            - author: Bernie
              date: 2026-07-18T00:00:00Z
              body: please fix this
        ---

        # Intro

        hello world [^c1]
    """.trimIndent() + "\n"

    @Test
    fun `parses front matter and comments`() {
        val doc = DocumentParser.parse(withComment)
        assertNotNull(doc.frontMatter)
        assertEquals(1, doc.comments.size)
        val c = doc.comments[0]
        assertEquals("c1", c.id)
        assertEquals(CommentStatus.OPEN, c.status)
        assertEquals("Intro", c.section)
        assertEquals(1, c.thread.size)
        assertEquals("Bernie", c.thread[0].author)
        assertTrue(doc.rawBody.contains("hello world [^c1]"))
    }

    @Test
    fun `parse then serialize then parse is idempotent`() {
        val doc = DocumentParser.parse(withComment)
        val again = DocumentParser.parse(DocumentParser.serialize(doc))
        assertEquals(doc.frontMatter, again.frontMatter)
        assertEquals(doc.rawBody, again.rawBody)
    }

    @Test
    fun `resolved fields serialize as snake_case and round-trip`() {
        val resolved = """
            ---
            mdreview:
              version: 1
              instructions: x
              comments:
              - id: c1
                status: resolved
                section: ''
                quote: hi
                thread:
                - author: Claude
                  date: 2026-07-18T00:00:00Z
                  body: done
                resolution: reworded
                resolved_by: Claude
                resolved_date: 2026-07-18T01:00:00Z
            ---

            body
        """.trimIndent() + "\n"
        val doc = DocumentParser.parse(resolved)
        val c = doc.comments[0]
        assertEquals(CommentStatus.RESOLVED, c.status)
        assertEquals("Claude", c.resolvedBy)
        val out = DocumentParser.serialize(doc)
        assertTrue(out.contains("resolved_by:"), "expected snake_case key in output")
        assertFalse(out.contains("resolvedBy"))
        assertEquals(doc.frontMatter, DocumentParser.parse(out).frontMatter)
    }

    @Test
    fun `a broken mdreview block still opens body-only and serializes byte-for-byte`() {
        // Missing required 'instructions' key -> will not decode.
        val broken = "---\nmdreview:\n  version: 1\n  comments: []\n---\n\n# Body\n\ntext\n"
        val doc = DocumentParser.parse(broken)
        assertNull(doc.frontMatter)
        assertNotNull(doc.brokenFrontMatter)
        assertTrue(doc.rawBody.contains("# Body"))
        assertTrue(doc.frontMatterIsUnreadable)
        // Serialize must hand the original back exactly.
        assertEquals(broken, DocumentParser.serialize(doc))
    }

    @Test
    fun `a plain markdown file has no front matter and serializes verbatim`() {
        val plain = "# Title\n\nJust prose, no mdreview.\n"
        val doc = DocumentParser.parse(plain)
        assertNull(doc.frontMatter)
        assertNull(doc.brokenFrontMatter)
        assertEquals(plain, doc.rawBody)
        assertEquals(plain, DocumentParser.serialize(doc))
    }

    @Test
    fun `nextCommentId skips used ids`() {
        val doc = DocumentParser.parse(withComment)
        assertEquals("c2", DocumentParser.nextCommentId(doc))
    }

    @Test
    fun `insertMarker anchors a quote that spans a line break`() {
        val body = "The quick brown\nfox jumps over"
        val out = DocumentParser.insertMarker("c1", "quick brown fox", body)
        assertNotNull(out)
        assertTrue(out!!.contains("[^c1]"))
        // Marker lands right after the located run.
        assertTrue(out.contains("fox[^c1]"))
    }

    @Test
    fun `insertMarker returns null when the quote is absent`() {
        assertNull(DocumentParser.insertMarker("c1", "nowhere", "some other text"))
    }

    @Test
    fun `section returns the nearest heading above a marker`() {
        val body = "# Top\n\nintro\n\n## Details\n\nsee here [^c1]\n"
        assertEquals("Details", DocumentParser.section("c1", body))
    }

    @Test
    fun `markerExists detects a marker`() {
        assertTrue(DocumentParser.markerExists("c1", "x [^c1] y"))
        assertFalse(DocumentParser.markerExists("c2", "x [^c1] y"))
    }
}
