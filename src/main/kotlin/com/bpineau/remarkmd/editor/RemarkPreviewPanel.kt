package com.bpineau.remarkmd.editor

import com.bpineau.remarkmd.core.Comments
import com.bpineau.remarkmd.core.DocumentParser
import com.bpineau.remarkmd.core.EditorScript
import com.bpineau.remarkmd.state.DocumentState
import com.bpineau.remarkmd.state.DocumentStateService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The read/comment pane: a JCEF browser hosting RemarkMD's own renderer (the extracted
 * `markdown.js` + `editor.css` inside `shell.html`). This is the IntelliJ counterpart of the Mac
 * app's `EditorView` (an NSViewRepresentable around a WKWebView).
 *
 * Load-once + queue: the shell HTML is loaded exactly once; JS is queued until the page's
 * `onLoadEnd` fires, mirroring the WKWebView `didFinish` discipline. Prose arrives via
 * `renderContent`, never a reload, so re-rendering after an edit does not scroll the reader away.
 *
 * The two WebKit message handlers (`selectionChanged`, `markerTapped`) become a single
 * [JBCefJSQuery] injected as `window.rmPost`, dispatched on a U+0001-delimited payload.
 *
 * Focus is not the panel's own — it lives in the per-file [DocumentState] shared with the comments
 * tool window. Tapping a marker sets it (which scrolls this pane and highlights the sidebar card);
 * a card click in the sidebar sets it (which scrolls this pane). The panel observes it and renders.
 */
class RemarkPreviewPanel(
    private val project: Project,
    private val file: VirtualFile,
    private val document: Document?,
    parent: Disposable,
) {
    private data class PendingSelection(val quote: String, val section: String)

    val component: JComponent = JPanel(BorderLayout())

    private val supported = JBCefApp.isSupported()
    private val browser: JBCefBrowser? = if (supported) JBCefBrowser() else null
    private val bridge: JBCefJSQuery? =
        browser?.let { JBCefJSQuery.create(it as com.intellij.ui.jcef.JBCefBrowserBase) }

    private val addButton = JButton("Add Comment").apply { isEnabled = false }
    private val selectionLabel = JLabel("Select a passage in the preview to comment on it.")

    private val docState: DocumentState = DocumentStateService.getInstance(project).forFile(file)

    private var loaded = false
    private val queued = mutableListOf<String>()
    private var lastRenderedBody: String? = null
    private var lastRenderedFocus: String? = null
    private var pending: PendingSelection? = null

    // Held so it can be removed on dispose; a lambda has no stable identity otherwise.
    private val focusListener: () -> Unit = { render() }

    init {
        if (browser == null) {
            component.add(
                JLabel("JCEF is not available in this IDE, so the RemarkMD preview cannot render."),
                BorderLayout.CENTER,
            )
        } else {
            Disposer.register(parent, browser)
            bridge?.let { Disposer.register(parent, it) }

            val toolbar = JPanel(BorderLayout()).apply {
                add(addButton, BorderLayout.WEST)
                add(selectionLabel, BorderLayout.CENTER)
            }
            component.add(toolbar, BorderLayout.NORTH)
            component.add(browser.component, BorderLayout.CENTER)

            addButton.addActionListener { addCommentFromSelection() }

            bridge?.addHandler { payload -> handlePost(payload); null }

            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    onPageLoaded()
                }
            }, browser.cefBrowser)

            browser.loadHTML(buildShellHtml())

            document?.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    ApplicationManager.getApplication().invokeLater { render() }
                }
            }, parent)

            // Someone else moved the focus (a marker tap here, or a card click in the sidebar).
            docState.addListener(focusListener)
            Disposer.register(parent, Disposable { docState.removeListener(focusListener) })

            // Initial render (queued until the page finishes loading).
            render()
        }
    }

    // MARK: - Rendering (Kotlin -> page)

    /**
     * Reconcile the page to the live document text and the shared focus. Reads both fresh every
     * time, so it converges no matter which of the two changed or in what order they were signalled
     * — and, because [DocumentState.setFocusedComment] calls its listeners synchronously, adding a
     * comment renders the new body AND scrolls to its marker in one pass, the marker present by then.
     */
    private fun render() {
        val newBody = document?.text ?: ""
        val newFocus = docState.focusedCommentId
        val cmds = EditorScript.commands(lastRenderedBody, newBody, lastRenderedFocus, newFocus)
        lastRenderedBody = newBody
        lastRenderedFocus = newFocus
        cmds.forEach { runJs(it) }
    }

    private fun runJs(js: String) {
        val b = browser ?: return
        if (loaded) b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
        else queued.add(js)
    }

    private fun onPageLoaded() {
        val b = browser ?: return
        val q = bridge
        if (q != null) {
            // Define window.rmPost so the page can call back into the plugin.
            val inject = "window.rmPost = function(p) { ${q.inject("p")} };"
            b.cefBrowser.executeJavaScript(inject, b.cefBrowser.url, 0)
        }
        loaded = true
        val pendingJs = queued.toList()
        queued.clear()
        pendingJs.forEach { b.cefBrowser.executeJavaScript(it, b.cefBrowser.url, 0) }
    }

    // MARK: - Page -> Kotlin

    private fun handlePost(payload: String) {
        val parts = payload.split('\u0001')
        when (parts.getOrNull(0)) {
            "markerTapped" -> {
                val id = parts.getOrNull(1) ?: return
                ApplicationManager.getApplication().invokeLater { docState.setFocusedComment(id) }
            }
            "selectionChanged" -> {
                val text = parts.getOrNull(1) ?: return
                val section = parts.getOrNull(2) ?: ""
                ApplicationManager.getApplication().invokeLater {
                    pending = PendingSelection(text, section)
                    addButton.isEnabled = true
                    selectionLabel.text = "Comment on: “" + text.take(60) +
                        (if (text.length > 60) "…" else "") + "”"
                }
            }
        }
    }

    // MARK: - Add comment (page selection -> Document mutation)

    private fun addCommentFromSelection() {
        val doc = document ?: return
        val sel = pending ?: return
        val body = Messages.showMultilineInputDialog(
            project,
            "Comment on:\n“${sel.quote}”",
            "Add Comment",
            "",
            null,
            null,
        ) ?: return
        if (body.isBlank()) return

        val author = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "Reviewer"
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

        var newCommentId: String? = null
        WriteCommandAction.runWriteCommandAction(project, "Add Comment", null, Runnable {
            val parsed = DocumentParser.parse(doc.text)
            val result = Comments.addComment(
                parsed, sel.quote, sel.section.ifEmpty { null }, body, author, timestamp,
            )
            if (result.commentId != null) {
                doc.setText(DocumentParser.serialize(result.doc))
                newCommentId = result.commentId
            }
        })

        // Focus the new comment: renders the freshly-inserted marker and scrolls to it in one pass,
        // and highlights the matching card in the sidebar.
        newCommentId?.let { docState.setFocusedComment(it) }

        pending = null
        addButton.isEnabled = false
        selectionLabel.text = "Select a passage in the preview to comment on it."
    }

    // MARK: - Shell assembly

    private fun buildShellHtml(): String {
        fun res(path: String): String =
            javaClass.getResourceAsStream(path)?.readBytes()?.toString(Charsets.UTF_8)
                ?: error("missing resource: $path")
        return res("/web/shell.html")
            .replace("__RM_CSS__", res("/web/editor.css"))
            .replace("__RM_MARKDOWN_JS__", res("/web/markdown.js"))
    }
}
