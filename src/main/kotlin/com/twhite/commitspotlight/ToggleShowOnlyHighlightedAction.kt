package com.twhite.commitspotlight

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/** Filters the Git Log down to just the commits currently tracked by [CommitHighlightService]. */
class ToggleShowOnlyHighlightedAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return project.getService(CommitHighlightService::class.java).isShowOnlyHighlighted()
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        project.getService(CommitHighlightService::class.java).setShowOnlyHighlighted(state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        val service = project?.getService(CommitHighlightService::class.java)
        val hasHighlights = service?.allHighlightedHashes()?.isNotEmpty() ?: false
        e.presentation.isEnabled = hasHighlights || (service?.isShowOnlyHighlighted() ?: false)
    }
}
