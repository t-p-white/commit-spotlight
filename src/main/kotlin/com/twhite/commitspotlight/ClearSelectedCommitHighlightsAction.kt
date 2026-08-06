package com.twhite.commitspotlight

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.vcs.log.VcsLogDataKeys
import java.io.File

/** Clears highlighting only for the commit(s) currently selected in the Git Log, leaving others intact. */
class ClearSelectedCommitHighlightsAction : AnAction() {

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
        val hashesToRemove = commits.map { it.hash }.toSet()
        val service = project.getService(CommitHighlightService::class.java)

        val batchHashes = service.snapshotBatchHashes()
        val needsRecompute = batchHashes.filter { (_, hashes) ->
            val remaining = hashes - hashesToRemove
            remaining.isNotEmpty() && remaining.size != hashes.size
        }

        if (needsRecompute.isEmpty()) {
            service.removeCommitsFromBatches(hashesToRemove, emptyMap())
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating Commit Highlights", true) {
            override fun run(indicator: ProgressIndicator) {
                val recomputed = needsRecompute.mapValues { (_, hashes) ->
                    val remainingHashStrings = (hashes - hashesToRemove).map { it.asString() }
                    GitDiffParser.changedLinesForCommits(repoRoot, remainingHashStrings)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        service.removeCommitsFromBatches(hashesToRemove, recomputed)
                    }
                }
            }
        })
    }
}
