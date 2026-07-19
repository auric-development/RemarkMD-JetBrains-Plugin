package com.bpineau.remarkmd

import com.bpineau.remarkmd.core.EditorScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports of the pure cases from RemarkMDTests/ImageTests.swift. The Mac app's sandbox / grant cases
 * (ImageAccess) are omitted deliberately: a JetBrains plugin is not sandboxed, so there is no
 * folder-grant dance and no ImageAccess counterpart.
 */
class ImageTest {

    private val docDir = "/Users/b/Documents/morg/reference"

    @Test
    fun `a relative image resolves against the document's folder, into our scheme`() {
        val src = EditorScript.imageUrl("../images/web/IMG_1298.jpg", docDir)
        assertEquals("rmimg://local/Users/b/Documents/morg/images/web/IMG_1298.jpg", src)
    }

    @Test
    fun `a path with spaces survives the round trip`() {
        val src = EditorScript.imageUrl("../my images/a photo.jpg", docDir)
        assertTrue(src.contains("%20"), "expected the space to be percent-encoded: $src")

        val file = EditorScript.fileForImageUrl(src)
        assertEquals("/Users/b/Documents/morg/my images/a photo.jpg", file)
    }

    @Test
    fun `an rmimg URL resolves back to the file it names`() {
        val src = EditorScript.imageUrl("../images/web/IMG_1298.jpg", docDir)
        val file = EditorScript.fileForImageUrl(src)
        assertEquals("/Users/b/Documents/morg/images/web/IMG_1298.jpg", file)
    }

    @Test
    fun `remote images are left alone`() {
        for (src in listOf(
            "https://example.com/a.png",
            "http://example.com/a.png",
            "data:image/png;base64,iVBORw0KGgo=",
        )) {
            assertEquals(src, EditorScript.imageUrl(src, docDir))
        }
    }

    @Test
    fun `an already-ours rmimg URL is left alone`() {
        val already = "rmimg://local/Users/b/x.png"
        assertEquals(already, EditorScript.imageUrl(already, docDir))
    }

    @Test
    fun `without a folder to resolve against, a relative image is left alone`() {
        assertEquals("../a.png", EditorScript.imageUrl("../a.png", null))
    }

    @Test
    fun `a non-rmimg URL resolves to no file`() {
        assertEquals(null, EditorScript.fileForImageUrl("https://example.com/a.png"))
        assertEquals(null, EditorScript.fileForImageUrl("../a.png"))
    }

    @Test
    fun `rewriting touches images and leaves links and prose alone`() {
        val body = """
            # Report

            ![panel](../images/web/IMG_1298.jpg)

            See [the memo](../other/memo.md).
        """.trimIndent()
        val out = EditorScript.rewritingImages(body, docDir)

        assertTrue(out.contains("![panel](rmimg://local/Users/b/Documents/morg/images/web/IMG_1298.jpg)"))
        assertTrue(out.contains("[the memo](../other/memo.md)")) // a link, not an image
        assertTrue(out.contains("# Report"))
    }

    @Test
    fun `rewriting leaves images alone when there is no folder`() {
        val body = "![panel](../images/web/IMG_1298.jpg)"
        assertEquals(body, EditorScript.rewritingImages(body, null))
    }

    @Test
    fun `every local image is found, and remote ones are not counted`() {
        val body = """
            ![a](../images/one.jpg)
            ![b](../images/two.png)
            ![c](https://example.com/three.png)
            ![d](sibling.gif)
        """.trimIndent()
        val files = EditorScript.localImageFiles(body, docDir)

        assertEquals(3, files.size)
        assertEquals(listOf("one.jpg", "two.png", "sibling.gif"), files.map { it.substringAfterLast('/') })
        assertEquals("/Users/b/Documents/morg/images/one.jpg", files[0])
    }

    @Test
    fun `a document with no images asks for nothing`() {
        assertTrue(EditorScript.localImageFiles("# Just prose", docDir).isEmpty())
    }

    @Test
    fun `localImageFiles without a folder returns nothing`() {
        assertTrue(EditorScript.localImageFiles("![a](../x.png)", null).isEmpty())
    }

    // MARK: - commands() rewrites the body before rendering

    @Test
    fun `the rendered payload carries the rewritten image source`() {
        val cmds = EditorScript.commands(
            oldBody = null, newBody = "![a](../images/one.jpg)",
            oldFocus = null, newFocus = null, directory = docDir,
        )
        val render = cmds.first()
        assertTrue(render.startsWith("renderContent("))
        assertTrue(render.contains("rmimg://local/Users/b/Documents/morg/images/one.jpg"))
    }

    @Test
    fun `commands without a directory leaves the image src untouched`() {
        val cmds = EditorScript.commands(
            oldBody = null, newBody = "![a](../images/one.jpg)",
            oldFocus = null, newFocus = null, directory = null,
        )
        val render = cmds.first()
        assertTrue(render.contains("../images/one.jpg"))
        assertFalse(render.contains("rmimg://"))
    }
}
