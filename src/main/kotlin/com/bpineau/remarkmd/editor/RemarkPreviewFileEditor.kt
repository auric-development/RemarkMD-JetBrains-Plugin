package com.bpineau.remarkmd.editor

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * The preview half of the RemarkMD editor: hosts [RemarkPreviewPanel]. Read-only by design —
 * editing happens in the paired text editor (the IDE's own Markdown editor), which is why this
 * side reports `isModified = false` and owns no document state of its own.
 */
class RemarkPreviewFileEditor(
    project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val document = FileDocumentManager.getInstance().getDocument(file)
    private val panel = RemarkPreviewPanel(project, document, this)

    override fun getComponent(): JComponent = panel.component
    override fun getPreferredFocusedComponent(): JComponent = panel.component
    override fun getName(): String = "Preview"

    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        // JCEF browser + JS query are disposed via the Disposer tree (registered with `this`).
    }
}
