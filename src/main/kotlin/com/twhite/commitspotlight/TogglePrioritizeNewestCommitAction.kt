package com.twhite.commitspotlight

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * Decides which highlight wins when two highlighted commits touch the same line: off (default)
 * means whichever commit was *highlighted* most recently wins; on means whichever commit is
 * chronologically *newest* wins, regardless of the order they were highlighted in.
 *
 * Checkable menu items in this environment don't render hover tooltips (verified — not specific
 * to this action), so the fuller explanation is shown as a notification on toggle instead.
 */
class TogglePrioritizeNewestCommitAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        CommitHighlighterSettings.getInstance().prioritizeNewestCommit

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CommitHighlighterSettings.getInstance().prioritizeNewestCommit = state

        val project = e.project
        project?.getService(CommitHighlightService::class.java)?.refreshAllHighlights()

        val message = if (state) {
            "When highlighted commits overlap on the same line, the chronologically newest " +
                "commit now wins, regardless of the order you highlighted them in."
        } else {
            "When highlighted commits overlap on the same line, whichever commit you " +
                "highlighted most recently now wins, regardless of its actual commit date."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CommitSpotlight.Notifications")
            .createNotification(
                "Prioritize Newest Commit: ${if (state) "On" else "Off"}",
                message,
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
