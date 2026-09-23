package com.twhite.commitspotlight

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcs.log.Hash
import java.io.File

/**
 * End-to-end coverage for the "an edit desyncs a highlight from what the commit actually touched"
 * fix: [CommitHighlightService] listens for document edits and drops just the highlighted spans
 * a live edit lands on, using [CommitHighlightService.currentlyHighlightedOriginalLines] as the
 * window into what's actually drawn. Needs a real [com.intellij.openapi.editor.Editor]/Document
 * pair — [CommitHighlightService.rangesOverlap]'s own boundary-condition coverage lives in
 * [CommitHighlightServiceRangesOverlapTest] as a plain unit test instead.
 */
class CommitHighlightServiceInvalidationTest : BasePlatformTestCase() {

    private class FakeHash(private val value: String) : Hash {
        override fun asString(): String = value
        override fun toShortString(): String = value.take(7)
    }

    private fun service(): CommitHighlightService = project.getService(CommitHighlightService::class.java)

    private fun highlight(file: VirtualFile, changedLines: Set<Int>) {
        val repoRoot = File(file.parent.path)
        val diffInfo = mapOf(file.name to FileDiffInfo(changedLines = changedLines))
        service().addHighlightBatch(repoRoot, diffInfo, setOf(FakeHash(changedLines.toString())), CommitHighlighterColors.DEFAULT)
    }

    /**
     * Runs [action] as a write command, then pumps the IDE event queue: the invalidation flow
     * updates state from an `invokeLater` (see [CommitHighlightService.onDocumentChanged]), which
     * — since [BasePlatformTestCase] runs the test body on the EDT — would otherwise still be
     * sitting unprocessed in the queue by the time a following assertion runs.
     */
    private fun edit(document: Document, action: Document.() -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { document.action() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            service().clearHighlights()
        } finally {
            super.tearDown()
        }
    }

    fun `test typing inside one line of a single-line highlighted block drops it`() {
        val file = myFixture.configureByText("Foo.txt", "one\ntwo\nthree\nfour\n").virtualFile
        myFixture.openFileInEditor(file)
        highlight(file, setOf(2))
        assertEquals(setOf(2), service().currentlyHighlightedOriginalLines(file))

        val document = myFixture.editor.document
        val insideLine2 = document.getLineStartOffset(1) + 1 // inside "two" (original line 2)
        edit(document) { insertString(insideLine2, "X") }

        assertEquals(emptySet<Int>(), service().currentlyHighlightedOriginalLines(file))
    }

    fun `test typing inside one line of a merged multi-line block drops the whole block`() {
        // Adjacent same-colored lines are merged into a single rounded highlight block (one
        // RangeHighlighter, one HighlightSpan) — see RoundedLineBackgroundRenderer. Invalidation
        // works at that same granularity: it can't un-highlight line 3 alone without re-flushing
        // the run, so editing anywhere inside the merged block drops the whole thing, not just
        // the specific line touched.
        val file = myFixture.configureByText("Foo.txt", "one\ntwo\nthree\nfour\n").virtualFile
        myFixture.openFileInEditor(file)
        highlight(file, setOf(2, 3))
        assertEquals(setOf(2, 3), service().currentlyHighlightedOriginalLines(file))

        val document = myFixture.editor.document
        val insideLine2 = document.getLineStartOffset(1) + 1 // inside "two" (original line 2)
        edit(document) { insertString(insideLine2, "X") }

        assertEquals(emptySet<Int>(), service().currentlyHighlightedOriginalLines(file))
    }

    fun `test typing outside a highlighted block leaves it intact`() {
        val file = myFixture.configureByText("Foo.txt", "one\ntwo\nthree\nfour\n").virtualFile
        myFixture.openFileInEditor(file)
        highlight(file, setOf(3))
        assertEquals(setOf(3), service().currentlyHighlightedOriginalLines(file))

        val document = myFixture.editor.document
        edit(document) { insertString(getLineStartOffset(0), "X") }

        assertEquals(setOf(3), service().currentlyHighlightedOriginalLines(file))
    }

    fun `test typing exactly at a highlighted block's boundary leaves it intact`() {
        val file = myFixture.configureByText("Foo.txt", "one\ntwo\nthree\nfour\n").virtualFile
        myFixture.openFileInEditor(file)
        highlight(file, setOf(2))
        assertEquals(setOf(2), service().currentlyHighlightedOriginalLines(file))

        // The highlighted block spans exactly [start of line 2, start of line 3); typing right at
        // its end boundary (start of line 3) is, per the non-greedy RangeMarker semantics that
        // rangesOverlap models, not "inside" it.
        val document = myFixture.editor.document
        edit(document) { insertString(getLineStartOffset(2), "X") }

        assertEquals(setOf(2), service().currentlyHighlightedOriginalLines(file))
    }

    fun `test deleting a highlighted line drops it`() {
        val file = myFixture.configureByText("Foo.txt", "one\ntwo\nthree\nfour\n").virtualFile
        myFixture.openFileInEditor(file)
        highlight(file, setOf(2))
        assertEquals(setOf(2), service().currentlyHighlightedOriginalLines(file))

        val document = myFixture.editor.document
        edit(document) { deleteString(getLineStartOffset(1), getLineEndOffset(1) + 1) }

        assertEquals(emptySet<Int>(), service().currentlyHighlightedOriginalLines(file))
    }

    fun `test clearing highlights resets invalidation so a re-highlighted line can show again`() {
        val file = myFixture.configureByText("Foo.txt", "one\ntwo\nthree\nfour\n").virtualFile
        myFixture.openFileInEditor(file)
        highlight(file, setOf(2))

        val document = myFixture.editor.document
        edit(document) { insertString(getLineStartOffset(1) + 1, "X") }
        assertEquals(emptySet<Int>(), service().currentlyHighlightedOriginalLines(file))

        service().clearHighlights()
        highlight(file, setOf(2))

        assertEquals(setOf(2), service().currentlyHighlightedOriginalLines(file))
    }
}
