package com.bpineau.remarkmd.action

import com.bpineau.remarkmd.core.ClaudeExport
import com.bpineau.remarkmd.core.DocumentParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection

/**
 * "Copy Open Comments for Claude" — the port of the Mac app's ⌘⇧K menu command / sidebar button.
 * Builds the [ClaudeExport] payload for the Markdown file in front and puts it on the system
 * clipboard, so the reviewer can paste the whole handoff (path, ids, instructions, threads) into a
 * Claude chat.
 *
 * The single formatter lives in [ClaudeExport]; this class is only the platform glue that finds the
 * file's [Document] and copies. It disables itself when there is nothing open to hand off, mirroring
 * the Swift command's `isEnabled`.
 */
class CopyForClaudeAction : AnAction() {

    private val markdownExts = setOf("md", "markdown", "mdown")

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = payloadFor(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val payload = payloadFor(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(payload))
    }

    /** The clipboard text for the event's Markdown file, or null (no file, not Markdown, none open). */
    private fun payloadFor(e: AnActionEvent): String? {
        val file: VirtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (file.extension?.lowercase() !in markdownExts) return null
        val document: Document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val parsed = DocumentParser.parse(document.text)
        return ClaudeExport.openComments(parsed, file.path)
    }
}
