package com.bpineau.remarkmd.ui

import com.bpineau.remarkmd.core.CommentCardModel
import com.bpineau.remarkmd.core.CommentStatus

/**
 * Renders a [CommentCardModel] to the HTML body a Swing `JBLabel` draws — the read-only part of a
 * card (section, quoted passage, the thread, resolution, a missing-marker warning, and the status).
 * The action buttons are real Swing components alongside it, not part of this string.
 *
 * Pure and self-contained so it can be unit-tested without a UI: every scrap of user- or
 * Claude-authored text is HTML-escaped, so a comment body containing `<`, `&` or a stray `<script>`
 * is shown verbatim, never interpreted.
 */
object CommentCardHtml {

    fun render(model: CommentCardModel, widthPx: Int = 220): String {
        val sb = StringBuilder()
        sb.append("<html><body style='width:").append(widthPx).append("px'>")

        if (model.section.isNotEmpty()) {
            sb.append("<div color='gray'><small>").append(esc(model.section)).append("</small></div>")
        }
        sb.append("<div><i>“").append(esc(model.quote)).append("”</i></div>")

        if (model.markerMissing) {
            sb.append("<div color='#c07000'><small>⚠ Context may have changed, ")
                .append("marker not found</small></div>")
        }

        sb.append("<hr>")

        for (note in model.thread) {
            sb.append("<div><b>").append(esc(note.author)).append("</b> ")
                .append("<small color='gray'>").append(esc(note.date)).append("</small></div>")
            sb.append("<div>").append(esc(note.body).replace("\n", "<br>")).append("</div>")
        }

        if (model.status == CommentStatus.RESOLVED && !model.resolution.isNullOrEmpty()) {
            sb.append("<div color='green'><small>✓ ").append(esc(model.resolution))
                .append("</small></div>")
        }

        val statusLabel = if (model.status == CommentStatus.OPEN) "open" else "resolved"
        sb.append("<div><small color='gray'>[").append(statusLabel).append("]</small></div>")

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
