package com.twhite.commitspotlight

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.vcs.log.VcsLogDataKeys
import java.io.File

class HighlightSelectedCommitsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)
        val commits = selection?.commits
        if (commits.isNullOrEmpty()) {
            e.presentation.isEnabled = false
            return
        }
        val service = e.project?.getService(CommitHighlightService::class.java)
        val allAlreadyHighlighted = service != null && commits.all { service.isHighlighted(it.hash) }
        e.presentation.isEnabled = !allAlreadyHighlighted
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return
        val commits = selection.commits
        if (commits.isEmpty()) return

        val repoRoot = File(commits.first().root.path)
        val hashStrings = commits.map { it.hash.asString() }
        val hashes = commits.map { it.hash }.toSet()
        val color = CommitHighlighterSettings.getInstance().color

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Computing Commit Diffs", true) {
            override fun run(indicator: ProgressIndicator) {
                val diffInfo = GitDiffParser.changedLinesForCommits(repoRoot, hashStrings)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (diffInfo.isEmpty()) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("CommitSpotlight.Notifications")
                            .createNotification(
                                "No changes found",
                                "Commit Spotlight couldn't find any changes for the selected commit(s). " +
                                    "Check that git is available and the commit(s) still exist in this repo " +
                                    "(merge commits aren't supported).",
                                NotificationType.WARNING
                            )
                            .notify(project)
                    } else {
                        project.getService(CommitHighlightService::class.java)
                            .addHighlightBatch(repoRoot, diffInfo, hashes, color)
                    }
                }
            }
        })
    }
}
