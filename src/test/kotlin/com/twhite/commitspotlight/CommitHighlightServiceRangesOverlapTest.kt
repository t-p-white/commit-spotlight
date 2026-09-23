package com.twhite.commitspotlight

import com.twhite.commitspotlight.CommitHighlightService.Companion.rangesOverlap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the half-open-interval overlap check that decides whether a live edit landed inside
 * an already-drawn highlight (and should invalidate it) purely as arithmetic, without needing a
 * real [com.intellij.openapi.editor.markup.RangeHighlighter] or platform test fixture — the
 * boundary cases here are exactly what [CommitHighlightService.onBeforeDocumentChange] relies on
 * to avoid both false positives (unrelated typing wiping a highlight) and false negatives (an
 * edit inside a highlighted block not being caught).
 */
class CommitHighlightServiceRangesOverlapTest {

    @Test
    fun `insertion strictly inside a span overlaps`() {
        // Typing a single character in the middle of highlighted text: offset 15, no old text
        // removed (a pure insertion), against a span covering [10, 20).
        assertTrue(rangesOverlap(15, 15, 10, 20))
    }

    @Test
    fun `insertion exactly at a span's start boundary does not overlap`() {
        // Matches how a non-greedy RangeMarker actually behaves: text typed exactly at the start
        // offset stays outside the marker, so nothing was actually swept into the highlight.
        assertFalse(rangesOverlap(10, 10, 10, 20))
    }

    @Test
    fun `insertion exactly at a span's end boundary does not overlap`() {
        assertFalse(rangesOverlap(20, 20, 10, 20))
    }

    @Test
    fun `insertion entirely before a span does not overlap`() {
        assertFalse(rangesOverlap(0, 0, 10, 20))
    }

    @Test
    fun `insertion entirely after a span does not overlap`() {
        assertFalse(rangesOverlap(25, 25, 10, 20))
    }

    @Test
    fun `deletion partially overlapping a span's start overlaps`() {
        assertTrue(rangesOverlap(5, 15, 10, 20))
    }

    @Test
    fun `deletion partially overlapping a span's end overlaps`() {
        assertTrue(rangesOverlap(15, 25, 10, 20))
    }

    @Test
    fun `deletion fully containing a span overlaps`() {
        assertTrue(rangesOverlap(0, 30, 10, 20))
    }

    @Test
    fun `deletion fully contained within a span overlaps`() {
        assertTrue(rangesOverlap(12, 18, 10, 20))
    }

    @Test
    fun `deletion exactly matching a span's bounds overlaps`() {
        assertTrue(rangesOverlap(10, 20, 10, 20))
    }

    @Test
    fun `deletion ending exactly where a span starts does not overlap`() {
        // Half-open semantics: a deletion of [0, 10) removes everything up to, but not
        // including, offset 10 — it never actually touches a span starting at 10.
        assertFalse(rangesOverlap(0, 10, 10, 20))
    }

    @Test
    fun `deletion starting exactly where a span ends does not overlap`() {
        assertFalse(rangesOverlap(20, 30, 10, 20))
    }

    @Test
    fun `zero-width span is never touched by an edit that only reaches its offset`() {
        // A deletion/modification anchor marker is zero-width; an edit has to strictly straddle
        // its point (not merely reach it) to count as touching it.
        assertFalse(rangesOverlap(10, 15, 15, 15))
        assertFalse(rangesOverlap(15, 20, 15, 15))
    }

    @Test
    fun `zero-width span is touched by an edit that strictly straddles its point`() {
        assertTrue(rangesOverlap(10, 20, 15, 15))
    }
}
