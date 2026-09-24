package com.twhite.commitspotlight

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction

/** Whether a highlighted file's editor tab gets tinted to match, independent of the in-editor highlight itself. */
class ToggleTabTintingAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        // A toggle you might flip back and forth while checking the result — same reasoning as
        // the color/opacity pickers keeping the menu open.
        e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        CommitHighlighterSettings.getInstance().tabTintingEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CommitHighlighterSettings.getInstance().tabTintingEnabled = state
        e.project?.getService(CommitHighlightService::class.java)?.refreshAllHighlights()
    }
}
