package com.bpineau.remarkmd.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens Markdown files as a split editor: the IDE's own Markdown text editor (the write side) on
 * one half, RemarkMD's comment/preview pane (the read side) on the other. Depending only on
 * `com.intellij.modules.platform`, this provider works in every JetBrains IDE.
 */
class RemarkFileEditorProvider : FileEditorProvider, DumbAware {

    private val extensions = setOf("md", "markdown", "mdown")

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.extension?.lowercase() in extensions

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = RemarkPreviewFileEditor(project, file)
        return TextEditorWithPreview(
            textEditor,
            preview,
            "RemarkMD",
            TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
        )
    }

    override fun getEditorTypeId(): String = "remarkmd-editor"

    // Hide the plain default text editor tab; our split (which contains a text editor) replaces it.
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
