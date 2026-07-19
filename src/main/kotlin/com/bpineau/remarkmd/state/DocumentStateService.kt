package com.bpineau.remarkmd.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out one [DocumentState] per file, so the preview pane and the comments tool window observe
 * the *same* state for whichever file is in front of the user. A light `@Service` — auto-registered,
 * no plugin.xml entry needed — scoped to the project.
 *
 * Keyed by [VirtualFile.getUrl] rather than the file object: it is a stable string, and the same
 * logical file may be represented by different VirtualFile instances over a session.
 */
@Service(Service.Level.PROJECT)
class DocumentStateService {

    private val states = ConcurrentHashMap<String, DocumentState>()

    fun forFile(file: VirtualFile): DocumentState = states.getOrPut(file.url) { DocumentState() }

    companion object {
        fun getInstance(project: Project): DocumentStateService =
            project.getService(DocumentStateService::class.java)
    }
}
