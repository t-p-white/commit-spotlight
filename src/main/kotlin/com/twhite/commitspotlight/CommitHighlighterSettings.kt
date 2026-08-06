package com.twhite.commitspotlight

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Holds the "active" color used for the *next* highlight run; each run snapshots it into its own batch. */
@State(name = "CommitHighlighterSettings", storages = [Storage("commitHighlighter.xml")])
class CommitHighlighterSettings : PersistentStateComponent<CommitHighlighterSettings.State> {

    class State {
        var colorId: String = CommitHighlighterColors.DEFAULT.id
        var alphaPercent: Int = 50
    }

    private var state = State()

    var colorId: String
        get() = state.colorId
        set(value) {
            state.colorId = value
        }

    var alphaPercent: Int
        get() = state.alphaPercent
        set(value) {
            state.alphaPercent = value.coerceIn(10, 100)
        }

    val color: HighlightColor
        get() = CommitHighlighterColors.byId(state.colorId)

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): CommitHighlighterSettings =
            ApplicationManager.getApplication().getService(CommitHighlighterSettings::class.java)
    }
}
