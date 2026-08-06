package com.twhite.commitspotlight

import com.intellij.openapi.project.Project
import com.intellij.vcs.log.VcsCommitStyleFactory
import com.intellij.vcs.log.VcsLogDataPack
import com.intellij.vcs.log.VcsLogHighlighter
import com.intellij.vcs.log.VcsLogUi
import com.intellij.vcs.log.VcsShortCommitDetails
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.ui.highlighters.VcsLogHighlighterFactory

/** Tints the entire row (all columns) using each commit's own batch color from [CommitHighlightService]. */
class SelectedCommitLogHighlighter(private val project: Project) : VcsLogHighlighter {

    override fun getStyle(
        commitId: Int,
        details: VcsShortCommitDetails,
        column: Int,
        isSelected: Boolean
    ): VcsLogHighlighter.VcsCommitStyle {
        val color = project.getService(CommitHighlightService::class.java).colorForHash(details.id)
            ?: return VcsLogHighlighter.VcsCommitStyle.DEFAULT
        return VcsCommitStyleFactory.background(color)
    }

    override fun update(dataPack: VcsLogDataPack, refreshHappened: Boolean) {
        // CommitHighlightService is queried live in getStyle(); nothing to cache here.
    }
}

class SelectedCommitLogHighlighterFactory : VcsLogHighlighterFactory {

    override fun createHighlighter(logData: VcsLogData, logUi: VcsLogUi): VcsLogHighlighter {
        val project = logData.project
        val mainUi = logUi as? MainVcsLogUi
        if (mainUi != null) {
            project.getService(CommitHighlightService::class.java).registerLogUi(mainUi)
        }
        return SelectedCommitLogHighlighter(project)
    }

    override fun getId(): String = "CommitSpotlight.SelectedCommitRowHighlighter"

    override fun getTitle(): String = "Commit Spotlight: Selected Commits"

    override fun showMenuItem(): Boolean = false
}
