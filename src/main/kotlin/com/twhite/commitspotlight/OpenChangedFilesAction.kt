package com.twhite.commitspotlight

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.vcs.log.VcsLogDataKeys
import java.io.File

/** Opens every file touched by the currently selected commit(s), reusing the same diff parsing as highlighting. */
class OpenChangedFilesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)
        e.presentation.isEnabled = selection != null && selection.commits.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return
        val commits = selection.commits
        if (commits.isEmpty()) return

        val repoRoot = File(commits.first().root.path)
        val hashStrings = commits.map { it.hash.asString() }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding Changed Files", true) {
            override fun run(indicator: ProgressIndicator) {
                val relativePaths = GitDiffParser.changedLinesForCommits(repoRoot, hashStrings).keys
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val fileEditorManager = FileEditorManager.getInstance(project)
                    val localFs = LocalFileSystem.getInstance()
                    for (relativePath in relativePaths) {
                        val virtualFile = localFs.findFileByIoFile(File(repoRoot, relativePath)) ?: continue
                        fileEditorManager.openFile(virtualFile, true)
                    }
                }
            }
        })
    }
}
