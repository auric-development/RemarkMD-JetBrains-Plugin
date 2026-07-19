package com.bpineau.remarkmd.core

import java.net.URI
import java.nio.file.Paths

/**
 * What the read pane should do about a change, expressed as JavaScript to run against the
 * already-loaded page. Ported from RemarkMD/Views/EditorScript.swift.
 *
 * Pure by design so the one rule that matters can be tested: a body change and a focus change are
 * independent, and a pass that carries both must render AND scroll. Creating a comment is exactly
 * that pass — it inserts a `[^cN]` marker into the body and focuses the new comment at once.
 */
object EditorScript {

    /**
     * The scheme the page uses to ask the plugin for a local image. It cannot be `file:` — JCEF, like
     * WKWebView, will not load `file:` subresources out of an HTML string loaded via `loadHTML`. The
     * plugin registers a handler for this scheme (see editor/RmImgSchemeHandler.kt) that reads the
     * bytes in Kotlin. Unlike the Mac app, a JetBrains plugin is NOT sandboxed, so there is no
     * security-scoped-bookmark grant dance: the IDE may read any file the user can.
     */
    const val IMAGE_SCHEME = "rmimg"

    private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /**
     * @param directory the absolute path of the folder the document lives in; image paths are
     *   resolved relative to it. Null for a document with no folder (unlikely in an IDE), in which
     *   case relative images are left alone.
     */
    fun commands(
        oldBody: String?,
        newBody: String,
        oldFocus: String?,
        newFocus: String?,
        directory: String? = null,
    ): List<String> {
        val out = mutableListOf<String>()

        if (oldBody != newBody) {
            // The focused id rides along: replacing innerHTML throws away the .active highlight,
            // so the render has to put it back itself. Images are rewritten to `rmimg:` first, so
            // the renderer only ever emits `<img>` tags the plugin can serve.
            val prepared = markerAnchors(rewritingImages(newBody, directory))
            out.add("renderContent(${jsonEncode(prepared)}, ${jsonEncode(newFocus ?: "")})")
        }

        if (oldFocus != newFocus) {
            out.add(
                if (newFocus != null) "scrollToMarker(${jsonEncode(newFocus)})"
                else "clearMarkerHighlight()",
            )
        }

        return out
    }

    /**
     * Points every local image at `rmimg:`, resolved against the document's folder. Remote and data
     * URLs are left exactly as they are — the page can fetch those itself. When [directory] is null,
     * relative images cannot be resolved and are left alone.
     */
    fun rewritingImages(body: String, directory: String?): String =
        Regex("""!\[([^\]]*)\]\(([^)]+)\)""").replace(body) { m ->
            val alt = m.groupValues[1]
            val src = m.groupValues[2]
            "![$alt](${imageUrl(src, directory)})"
        }

    /**
     * @return [src] untouched if it is remote, already ours, or unresolvable; otherwise an `rmimg:`
     *   URL naming the absolute file (percent-encoded, spaces and all).
     */
    fun imageUrl(src: String, directory: String?): String {
        val remote = setOf("http", "https", "data", IMAGE_SCHEME)
        schemeOf(src)?.let { if (it in remote) return src }
        if (directory == null) return src
        val resolved = try {
            Paths.get(directory).resolve(src).normalize().toString()
        } catch (e: Exception) {
            return src
        }
        return try {
            // URI(scheme, host, path, fragment) percent-encodes the path (spaces -> %20) and keeps
            // '/' as separators — the counterpart to the Mac app's URLComponents.
            URI(IMAGE_SCHEME, "local", resolved, null).toString()
        } catch (e: Exception) {
            src
        }
    }

    /**
     * The absolute file path an `rmimg:` URL is asking for, or null if [url] is not one of ours.
     * The scheme handler calls this on the raw URL JCEF hands it.
     */
    fun fileForImageUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        if (!IMAGE_SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val path = uri.path?.takeIf { it.isNotEmpty() } ?: return null // percent-decoded by URI
        return try {
            Paths.get(path).normalize().toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Every local image a document refers to, resolved to an absolute path. Remote images are not
     * counted. Ported from the Mac app for parity; the plugin does not need a grant, so nothing
     * currently consumes it beyond its tests.
     */
    fun localImageFiles(body: String, directory: String?): List<String> {
        if (directory == null) return emptyList()
        val remote = setOf("http", "https", "data")
        val out = mutableListOf<String>()
        Regex("""!\[[^\]]*\]\(([^)]+)\)""").findAll(body).forEach { m ->
            val src = m.groupValues[1]
            schemeOf(src)?.let { if (it in remote) return@forEach }
            try {
                out.add(Paths.get(directory).resolve(src).normalize().toString())
            } catch (e: Exception) {
                // skip an unresolvable path rather than fail the whole list
            }
        }
        return out
    }

    /** The lowercased URL scheme of [src], or null if it has none (a relative path). */
    private fun schemeOf(src: String): String? =
        schemeRegex.find(src)?.groupValues?.get(1)?.lowercase()

    /** Rewrites `[^cN]` markers into clickable anchors. Result is raw HTML the renderer passes through. */
    fun markerAnchors(body: String): String =
        Regex("""\[\^(c\d+)\]""").replace(body) { m ->
            val id = m.groupValues[1]
            "<a id='$id' class='rm-marker' onclick='markerTapped(\"$id\")'><sup>[^$id]</sup></a>"
        }

    /**
     * A JSON string literal — quotes included — for use as a JavaScript argument. Escapes `<`,`>`,`&`
     * as well, so the literal stays safe even if it ever lands inside a `<script>` block again.
     */
    fun jsonEncode(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '<' -> sb.append("\\u003C")
                '>' -> sb.append("\\u003E")
                '&' -> sb.append("\\u0026")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
