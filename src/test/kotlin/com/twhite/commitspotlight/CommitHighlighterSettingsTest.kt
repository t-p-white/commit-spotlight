package com.twhite.commitspotlight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [CommitHighlighterSettings.alphaPercent]'s setter clamps to [10, 100], but
 * [CommitHighlighterSettings.loadState] used to assign the deserialized [CommitHighlighterSettings.State]
 * directly, bypassing that clamp — so a persisted value outside that range (a hand-edited config
 * file, or one written by a version with a different valid range) would load un-coerced. These
 * exercise `loadState` directly, without a platform fixture, since it needs no
 * `ApplicationManager`-backed service lookup.
 */
class CommitHighlighterSettingsTest {

    @Test
    fun `loadState clamps an out-of-range persisted alphaPercent above the max`() {
        val settings = CommitHighlighterSettings()
        val persisted = CommitHighlighterSettings.State().apply { alphaPercent = 250 }

        settings.loadState(persisted)

        assertEquals(100, settings.alphaPercent)
    }

    @Test
    fun `loadState clamps an out-of-range persisted alphaPercent below the min`() {
        val settings = CommitHighlighterSettings()
        val persisted = CommitHighlighterSettings.State().apply { alphaPercent = 0 }

        settings.loadState(persisted)

        assertEquals(10, settings.alphaPercent)
    }

    @Test
    fun `loadState leaves an in-range persisted alphaPercent untouched`() {
        val settings = CommitHighlighterSettings()
        val persisted = CommitHighlighterSettings.State().apply { alphaPercent = 42 }

        settings.loadState(persisted)

        assertEquals(42, settings.alphaPercent)
    }
}
