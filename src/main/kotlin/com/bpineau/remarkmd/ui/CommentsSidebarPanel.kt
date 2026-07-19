package com.bpineau.remarkmd.ui

import com.bpineau.remarkmd.core.BrokenFrontMatter
import com.bpineau.remarkmd.core.ClaudeExport
import com.bpineau.remarkmd.core.CommentCardModel
import com.bpineau.remarkmd.core.CommentStatus
import com.bpineau.remarkmd.core.DocumentParser
import com.bpineau.remarkmd.core.SidebarModel
import com.bpineau.remarkmd.editor.CommentActions
import com.bpineau.remarkmd.state.DocumentState
import com.bpineau.remarkmd.state.DocumentStateService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The comments tool window content — the port of the Mac app's `SidebarView`. Shows one card per
 * comment for whichever Markdown file is in front (id/section/quote/thread/status), hides resolved
 * ones behind a toggle, and refreshes when the document changes or the front file changes.
 *
 * Two-way linked with the preview through the per-file [DocumentState]: clicking a card focuses its
 * comment (scrolling the preview to the marker), and a marker tapped in the preview highlights the
 * matching card. Per-card Reply/Resolve/Reopen/Delete run through [CommentActions] (undo-able writes).
 */
class CommentsSidebarPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val showResolvedCheck = JBCheckBox("Show resolved", false)
    private val copyForClaudeButton = JButton("Copy for Claude").apply {
        putClientProperty("ActionToolbar.smallVariant", true)
        isFocusable = false
        toolTipText = "Copy the open comments (path, ids, instructions, threads) for pasting into Claude"
    }
    private val cardsContainer = JPanel()

    private var currentFile: VirtualFile? = null
    private var currentDocument: Document? = null
    private var currentDocState: DocumentState? = null

    /** Listeners bound to the *current* file; disposed and rebuilt when the front file changes. */
    private var fileScope: Disposable? = null
    private val focusListener: () -> Unit = { refresh() }

    private val markdownExts = setOf("md", "markdown", "mdown")

    init {
        val title = JBLabel("Comments").apply { font = font.deriveFont(Font.BOLD) }
        val headerEast = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(copyForClaudeButton)
            add(showResolvedCheck)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            add(title, BorderLayout.WEST)
            add(headerEast, BorderLayout.EAST)
        }
        showResolvedCheck.addActionListener { refresh() }
        copyForClaudeButton.addActionListener { onCopyForClaude() }

        cardsContainer.layout = BoxLayout(cardsContainer, BoxLayout.Y_AXIS)
        cardsContainer.border = JBUI.Borders.empty(4)
        val scroll = JBScrollPane(cardsContainer).apply { border = JBUI.Borders.empty() }

        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    bindTo(event.newFile)
                }
            },
        )

        bindTo(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    private fun isMarkdown(file: VirtualFile?): Boolean =
        file?.extension?.lowercase() in markdownExts

    /** Point the sidebar at [file], rebuilding the document + focus listeners for it. */
    private fun bindTo(file: VirtualFile?) {
        val target = file?.takeIf { isMarkdown(it) }
        if (target?.url == currentFile?.url) {
            refresh()
            return
        }

        fileScope?.let { Disposer.dispose(it) }
        fileScope = null
        currentFile = target
        currentDocument = null
        currentDocState = null

        if (target != null) {
            val doc = FileDocumentManager.getInstance().getDocument(target)
            currentDocument = doc
            val ds = DocumentStateService.getInstance(project).forFile(target)
            currentDocState = ds

            val scope = Disposer.newDisposable("RemarkMD sidebar file scope")
            Disposer.register(this, scope)
            fileScope = scope

            doc?.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    ApplicationManager.getApplication().invokeLater { refresh() }
                }
            }, scope)

            ds.addListener(focusListener)
            Disposer.register(scope, Disposable { ds.removeListener(focusListener) })
        }

        refresh()
    }

    /** Rebuild every card from the live document. Cheap enough to run on any change. */
    private fun refresh() {
        cardsContainer.removeAll()
        val doc = currentDocument
        copyForClaudeButton.isEnabled = false

        if (currentFile == null || doc == null) {
            cardsContainer.add(messageLabel("Open a Markdown file to see its comments."))
        } else {
            val parsed = DocumentParser.parse(doc.text)
            copyForClaudeButton.isEnabled = parsed.openComments.isNotEmpty()
            parsed.brokenFrontMatter?.let { cardsContainer.add(brokenBanner(it)) }

            val cards = SidebarModel.buildCards(parsed, showResolvedCheck.isSelected)
            if (cards.isEmpty() && parsed.brokenFrontMatter == null) {
                cardsContainer.add(
                    messageLabel(
                        if (parsed.comments.isEmpty())
                            "No comments yet. Select text in the preview to comment on it."
                        else
                            "No open comments. Turn on “Show resolved” to see resolved ones.",
                    ),
                )
            }

            val focused = currentDocState?.focusedCommentId
            for (m in cards) cardsContainer.add(cardComponent(m, m.id == focused, doc))
        }

        cardsContainer.add(Box.createVerticalGlue())
        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    // MARK: - Components

    private fun messageLabel(text: String): JComponent =
        JBLabel("<html><body style='width:220px'>$text</body></html>").apply {
            border = JBUI.Borders.empty(12, 8)
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = JBColor.GRAY
        }

    private fun brokenBanner(broken: BrokenFrontMatter): JComponent {
        val html = "<html><body style='width:220px'><b>The comments in this file can't be read</b>" +
            "<br>${escape(broken.reason)}<br><small>${escape(broken.detail)}</small></body></html>"
        return JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xE0A030, 0xC08020), 1, true),
                JBUI.Borders.empty(8),
            )
            add(JBLabel(html).apply { foreground = JBColor(0xA0600A, 0xE0A030) }, BorderLayout.CENTER)
            maximumSize = Dimension(Integer.MAX_VALUE, preferredSize.height)
        }
    }

    private fun cardComponent(model: CommentCardModel, focused: Boolean, doc: Document): JComponent {
        val card = JPanel(BorderLayout())
        card.alignmentX = Component.LEFT_ALIGNMENT

        val borderColor = if (focused) JBColor(0x3B82F6, 0x589DF6) else JBColor.border()
        val width = if (focused) 2 else 1
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, width, true),
            JBUI.Borders.empty(8),
        )

        card.add(JBLabel(CommentCardHtml.render(model)), BorderLayout.CENTER)
        card.add(actionRow(model, doc), BorderLayout.SOUTH)

        // Clicking anywhere on the card (outside a button) focuses this comment.
        card.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                currentDocState?.setFocusedComment(model.id)
            }
        })

        card.maximumSize = Dimension(Integer.MAX_VALUE, card.preferredSize.height)
        return card
    }

    private fun actionRow(model: CommentCardModel, doc: Document): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }

        row.add(linkButton("Reply") { onReply(model.id, doc) })
        if (model.status == CommentStatus.OPEN) {
            row.add(linkButton("Resolve") { onResolve(model.id, doc) })
        } else {
            row.add(linkButton("Reopen") { onReopen(model.id, doc) })
        }
        row.add(linkButton("Delete") { onDelete(model.id, doc) })
        return row
    }

    private fun linkButton(text: String, action: () -> Unit): JButton =
        JButton(text).apply {
            putClientProperty("ActionToolbar.smallVariant", true)
            isFocusable = false
            addActionListener { action() }
        }

    // MARK: - Actions

    private fun onReply(id: String, doc: Document) {
        val body = Messages.showMultilineInputDialog(
            project, "Reply:", "Reply to Comment", "", null, null,
        ) ?: return
        if (body.isBlank()) return
        CommentActions.reply(project, doc, id, body)
    }

    private fun onResolve(id: String, doc: Document) {
        val note = Messages.showInputDialog(
            project, "Resolution note (optional):", "Resolve Comment", null,
        ) ?: return // cancelled
        CommentActions.resolve(project, doc, id, note.ifBlank { null })
    }

    private fun onReopen(id: String, doc: Document) {
        CommentActions.reopen(project, doc, id)
    }

    /** Build the ⌘⇧K handoff for the current file and put it on the clipboard. */
    private fun onCopyForClaude() {
        val doc = currentDocument ?: return
        val parsed = DocumentParser.parse(doc.text)
        val payload = ClaudeExport.openComments(parsed, currentFile?.path) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(payload))
    }

    private fun onDelete(id: String, doc: Document) {
        val choice = Messages.showYesNoDialog(
            project, "Delete comment $id? This removes its [^$id] marker too.",
            "Delete Comment", "Delete", "Cancel", Messages.getWarningIcon(),
        )
        if (choice != Messages.YES) return
        if (currentDocState?.focusedCommentId == id) currentDocState?.setFocusedComment(null)
        CommentActions.delete(project, doc, id)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun dispose() {
        // Message-bus connection and the current file scope are children of `this` in the Disposer.
    }
}
