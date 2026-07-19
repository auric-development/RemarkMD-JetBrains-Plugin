package com.bpineau.remarkmd

import com.bpineau.remarkmd.editor.pathIsUnderRoot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The security boundary for the `rmimg:` file reader: a document's image src is only served if the
 * file's canonical path sits under an allowed root. These cover the pure containment decision; the
 * canonicalization (symlink/`..` resolution) and size cap live in the handler and run against the
 * real filesystem.
 */
class RmImgAccessTest {

    private val project = "/Users/b/Documents/morg"

    @Test
    fun `a file inside the allowed root is served`() {
        assertTrue(pathIsUnderRoot("/Users/b/Documents/morg/images/web/photo.jpg", listOf(project)))
    }

    @Test
    fun `a sibling-of-document folder under the project root is served`() {
        // The document lives in morg/reference, images in morg/images — both under the project root.
        assertTrue(pathIsUnderRoot("/Users/b/Documents/morg/images/a.png", listOf(project)))
    }

    @Test
    fun `the root directory itself counts as inside`() {
        assertTrue(pathIsUnderRoot(project, listOf(project)))
    }

    @Test
    fun `a file outside every root is refused`() {
        assertFalse(pathIsUnderRoot("/etc/passwd", listOf(project)))
        assertFalse(pathIsUnderRoot("/Users/b/.ssh/id_rsa", listOf(project)))
    }

    @Test
    fun `a sibling directory sharing a name prefix is not treated as inside`() {
        // "/Users/b/Documents/morgan" must NOT count as being under "/Users/b/Documents/morg".
        assertFalse(pathIsUnderRoot("/Users/b/Documents/morgan/secret.png", listOf(project)))
    }

    @Test
    fun `with no roots registered nothing is served`() {
        assertFalse(pathIsUnderRoot("/Users/b/Documents/morg/images/a.png", emptyList()))
    }

    @Test
    fun `an empty candidate path is refused`() {
        assertFalse(pathIsUnderRoot("", listOf(project)))
    }

    @Test
    fun `any one of several roots admits the file`() {
        val roots = listOf("/Users/b/projA", "/Users/b/projB")
        assertTrue(pathIsUnderRoot("/Users/b/projB/img/a.png", roots))
        assertFalse(pathIsUnderRoot("/Users/b/projC/img/a.png", roots))
    }
}
