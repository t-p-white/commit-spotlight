package com.twhite.commitspotlight

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.util.ui.ColorIcon
import com.intellij.vcs.log.VcsLogDataKeys
import java.io.File

/**
 * Sets which color the *next* "Highlight Changes From Selected Commit(s)" run will use, and
 * live-recolors whichever commit(s) are currently selected in the Git Log (if already
 * highlighted) so the change is visible immediately, per-commit rather than per-run.
 */
class ChangeHighlightColorActionGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // The XML-declared icon is just a static fallback; show the actually-active color instead.
    override fun update(e: AnActionEvent) {
        val activeColor = CommitHighlighterSettings.getInstance().color
        e.presentation.icon = ColorIcon(16, activeColor.toJBColor())
        e.presentation.text = "Highlight Color: ${activeColor.displayName}"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        CommitHighlighterColors.ALL.map { color -> buildColorAction(color) }.toTypedArray()

    // Plain AnAction rather than ToggleAction: checkable menu items in this environment show
    // only the checkmark and suppress the custom icon, so the color swatch never rendered.
    // A text checkmark stands in for the "currently active" indicator instead.
    private fun buildColorAction(color: HighlightColor): AnAction {
        return object : AnAction(color.displayName, null, ColorIcon(16, color.toJBColor())) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

            override fun update(e: AnActionEvent) {
                val isActive = CommitHighlighterSettings.getInstance().colorId == color.id
                e.presentation.text = if (isActive) "${color.displayName} ✓" else color.displayName
                e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            }

            override fun actionPerformed(e: AnActionEvent) {
                CommitHighlighterSettings.getInstance().colorId = color.id
                recolorSelectedCommits(e, color)
                // Forces the action system to re-poll presentations now, so the parent group's
                // "Highlight Color: X" label refreshes even while this popup stays open
                // (KeepPopupOnPerform.Always) instead of waiting for the whole menu to reopen.
                ActivityTracker.getInstance().inc()
            }
        }
    }

    private fun recolorSelectedCommits(e: AnActionEvent, color: HighlightColor) {
        val project = e.project ?: return
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return
        val commits = selection.commits
        if (commits.isEmpty()) return

        val repoRoot = File(commits.first().root.path)
        val hashesToRecolor = commits.map { it.hash }.toSet()
        val service = project.getService(CommitHighlightService::class.java)

        val batchHashes = service.snapshotBatchHashes()
        val needsSplit = batchHashes.filter { (_, hashes) ->
            val recolorSubset = hashes intersect hashesToRecolor
            recolorSubset.isNotEmpty() && recolorSubset != hashes
        }

        if (needsSplit.isEmpty()) {
            service.recolorCommits(hashesToRecolor, color, emptyMap())
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating Commit Highlights", true) {
            override fun run(indicator: ProgressIndicator) {
                val recomputed = needsSplit.mapValues { (_, hashes) ->
                    val recolorSubset = hashes intersect hashesToRecolor
                    val remaining = hashes - hashesToRecolor
                    val remainingDiff = GitDiffParser.changedLinesForCommits(repoRoot, remaining.map { it.asString() })
                    val recoloredDiff = GitDiffParser.changedLinesForCommits(repoRoot, recolorSubset.map { it.asString() })
                    RecolorSplit(remainingDiff, recoloredDiff)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        service.recolorCommits(hashesToRecolor, color, recomputed)
                    }
                }
            }
        })
    }
}
