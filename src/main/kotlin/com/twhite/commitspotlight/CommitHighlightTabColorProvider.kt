package com.twhite.commitspotlight

import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/** Tints a file's editor tab with the same color as its in-editor highlight, when it has one. */
class CommitHighlightTabColorProvider : EditorTabColorProvider {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        project.getService(CommitHighlightService::class.java).colorForFile(file)
}
