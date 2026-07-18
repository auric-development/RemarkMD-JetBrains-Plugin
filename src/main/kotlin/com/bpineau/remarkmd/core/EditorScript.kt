package com.bpineau.remarkmd.core

/**
 * What the read pane should do about a change, expressed as JavaScript to run against the
 * already-loaded page. Ported from RemarkMD/Views/EditorScript.swift.
 *
 * Pure by design so the one rule that matters can be tested: a body change and a focus change are
 * independent, and a pass that carries both must render AND scroll. Creating a comment is exactly
 * that pass — it inserts a `[^cN]` marker into the body and focuses the new comment at once.
 */
object EditorScript {

    fun commands(
        oldBody: String?,
        newBody: String,
        oldFocus: String?,
        newFocus: String?,
    ): List<String> {
        val out = mutableListOf<String>()

        if (oldBody != newBody) {
            // The focused id rides along: replacing innerHTML throws away the .active highlight,
            // so the render has to put it back itself.
            val prepared = markerAnchors(newBody)
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
