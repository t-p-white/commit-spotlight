package com.twhite.commitspotlight

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform

/** Global opacity applied to every highlight color; adapts across light/dark IDE themes better than a fixed brightness would, since it blends toward whatever's underneath. */
class ChangeHighlightAlphaActionGroup : DefaultActionGroup() {

    companion object {
        private val LEVELS = listOf(100, 90, 80, 70, 60, 50, 40, 30, 20, 10)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // Correct every time this group's menu is freshly built, same caveat as
    // ChangeHighlightColorActionGroup: won't visibly change mid-session if you pick a different
    // level while this menu stays open without closing it — see that class for why.
    override fun update(e: AnActionEvent) {
        e.presentation.text = "Highlight Opacity: ${CommitHighlighterSettings.getInstance().alphaPercent}%"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        LEVELS.map { percent -> buildAlphaAction(percent) }.toTypedArray()

    private fun buildAlphaAction(percent: Int): AnAction {
        return object : AnAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

            override fun update(e: AnActionEvent) {
                val isActive = CommitHighlighterSettings.getInstance().alphaPercent == percent
                e.presentation.text = if (isActive) "$percent% ✓" else "$percent%"
                e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            }

            override fun actionPerformed(e: AnActionEvent) {
                CommitHighlighterSettings.getInstance().alphaPercent = percent
                e.project?.getService(CommitHighlightService::class.java)?.refreshAllHighlights()
            }
        }
    }
}
