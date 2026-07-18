package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.EditorScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ports of RemarkMDTests/EditorScriptTests (the pure render/scroll decision layer). */
class EditorScriptTest {

    @Test
    fun `first render emits renderContent only`() {
        val cmds = EditorScript.commands(oldBody = null, newBody = "body", oldFocus = null, newFocus = null)
        assertEquals(1, cmds.size)
        assertTrue(cmds[0].startsWith("renderContent("))
    }

    @Test
    fun `adding a comment renders AND scrolls`() {
        // Body changed (marker inserted) and focus moved to the new comment in the same pass.
        val cmds = EditorScript.commands(oldBody = "hi", newBody = "hi [^c1]", oldFocus = null, newFocus = "c1")
        assertEquals(2, cmds.size)
        assertTrue(cmds[0].startsWith("renderContent("))
        assertTrue(cmds[1].startsWith("scrollToMarker("))
    }

    @Test
    fun `focus change alone scrolls without re-render`() {
        val cmds = EditorScript.commands(oldBody = "b", newBody = "b", oldFocus = "c1", newFocus = "c2")
        assertEquals(listOf("scrollToMarker(\"c2\")"), cmds)
    }

    @Test
    fun `clearing focus clears the highlight`() {
        val cmds = EditorScript.commands(oldBody = "b", newBody = "b", oldFocus = "c1", newFocus = null)
        assertEquals(listOf("clearMarkerHighlight()"), cmds)
    }

    @Test
    fun `no change emits nothing`() {
        val cmds = EditorScript.commands(oldBody = "b", newBody = "b", oldFocus = "c1", newFocus = "c1")
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun `markerAnchors wraps markers in clickable anchors`() {
        val html = EditorScript.markerAnchors("see this [^c1] now")
        assertTrue(html.contains("onclick='markerTapped(\"c1\")'"))
        assertTrue(html.contains("class='rm-marker'"))
    }

    @Test
    fun `jsonEncode produces a quoted literal and escapes markup`() {
        assertEquals("\"a\\\"b\"", EditorScript.jsonEncode("a\"b"))
        val encoded = EditorScript.jsonEncode("<b> & </b>")
        assertTrue(encoded.contains("\\u003C"))
        assertTrue(encoded.contains("\\u0026"))
        assertTrue(encoded.contains("\\u003E"))
    }
}
