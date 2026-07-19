package com.bpineau.remarkmd.state

/**
 * Per-file UI state linking the read preview and the comments sidebar — the port of the Mac app's
 * `DocumentState` (RemarkMD/Views/DocumentState.swift). The only piece the two panes actually
 * share here is `focusedCommentId`: the single source of truth for which comment is highlighted.
 *
 * Both panes set it and both observe it. Tapping a `[^cN]` marker in the preview focuses the
 * matching card; clicking a card scrolls the preview to its marker and highlights it. Setting the
 * id notifies every listener, once, and only when it actually changed — so there is no feedback
 * loop even though the setter is reachable from both sides.
 *
 * EDT-only, like all Swing state: created, read, mutated and observed on the event dispatch thread.
 */
class DocumentState {

    var focusedCommentId: String? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Focus a comment (or clear focus with null). No-op — and no notification — when unchanged. */
    fun setFocusedComment(id: String?) {
        if (focusedCommentId == id) return
        focusedCommentId = id
        // Snapshot: a listener may remove itself (or another) while reacting.
        listeners.toList().forEach { it() }
    }
}
