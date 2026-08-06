package com.twhite.commitspotlight

import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import java.awt.Font
import java.io.File
import java.lang.ref.WeakReference

private data class HighlightBatch(
    val color: HighlightColor,
    val hashes: Set<Hash>,
    val diffInfoByPath: Map<String, FileDiffInfo>
)

/** Diff info recomputed for each half of a batch being split by [CommitHighlightService.recolorCommits]. */
data class RecolorSplit(
    val remainingDiff: Map<String, FileDiffInfo>,
    val recoloredDiff: Map<String, FileDiffInfo>
)

/**
 * Project-level; owns the currently-applied commit highlights across open editors and the
 * Git Log table. Each "Highlight" action run adds its own colored batch rather than replacing
 * the previous one, so commits highlighted in separate runs keep their own distinct color.
 */
class CommitHighlightService(private val project: Project) : Disposable {

    private data class TrackedHighlight(val markupModel: MarkupModel, val highlighter: RangeHighlighter)

    private var repoRoot: File? = null
    private val batches = mutableListOf<HighlightBatch>()

    private val appliedHighlighters = mutableMapOf<VirtualFile, MutableList<TrackedHighlight>>()
    private val appliedInlays = mutableMapOf<VirtualFile, MutableList<Inlay<*>>>()
    private val registeredLogUis = mutableListOf<WeakReference<MainVcsLogUi>>()
    private var showOnlyHighlighted = false

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    applyToFile(file)
                }
            }
        )
    }

    fun addHighlightBatch(
        newRepoRoot: File,
        diffInfoByPath: Map<String, FileDiffInfo>,
        hashes: Set<Hash>,
        color: HighlightColor
    ) {
        repoRoot = newRepoRoot
        batches.add(HighlightBatch(color, hashes, diffInfoByPath))
        reapplyAllEditors()
        repaintLogTable()
    }

    fun clearHighlights() {
        val hadBatches = batches.isNotEmpty()
        batches.clear()
        for (tracked in appliedHighlighters.values) {
            removeTracked(tracked)
        }
        appliedHighlighters.clear()
        for (inlays in appliedInlays.values) {
            disposeInlays(inlays)
        }
        appliedInlays.clear()
        repoRoot = null
        if (showOnlyHighlighted) {
            showOnlyHighlighted = false
            applyHighlightFilter()
        }
        if (hadBatches) {
            repaintLogTable()
        }
    }

    fun isHighlighted(hash: Hash): Boolean = batches.any { hash in it.hashes }

    /** Redraws everything with no data change — used when a global setting like alpha changes. */
    fun refreshAllHighlights() {
        reapplyAllEditors()
        repaintLogTable()
    }

    /** Color of the most recent batch containing [hash], or null if it isn't highlighted. */
    fun colorForHash(hash: Hash): JBColor? =
        batches.lastOrNull { hash in it.hashes }?.color?.toJBColor()

    /**
     * Recolors [hashesToRecolor] to [newColor]. A batch entirely covered by [hashesToRecolor]
     * just gets a new color; a batch only partially covered is split in two using
     * [recomputedSplits] (diff info recomputed separately for the recolored vs. remaining
     * commits, since a batch's stored diff is a union across all its commits).
     */
    fun recolorCommits(
        hashesToRecolor: Set<Hash>,
        newColor: HighlightColor,
        recomputedSplits: Map<Int, RecolorSplit>
    ) {
        val newBatches = mutableListOf<HighlightBatch>()
        for ((i, batch) in batches.withIndex()) {
            val recolorSubset = batch.hashes intersect hashesToRecolor
            when {
                recolorSubset.isEmpty() -> newBatches.add(batch)
                recolorSubset == batch.hashes -> newBatches.add(batch.copy(color = newColor))
                else -> {
                    val split = recomputedSplits[i]
                    if (split == null) {
                        newBatches.add(batch)
                        continue
                    }
                    val remaining = batch.hashes - hashesToRecolor
                    newBatches.add(batch.copy(hashes = remaining, diffInfoByPath = split.remainingDiff))
                    newBatches.add(HighlightBatch(newColor, recolorSubset, split.recoloredDiff))
                }
            }
        }
        batches.clear()
        batches.addAll(newBatches)
        reapplyAllEditors()
        repaintLogTable()
    }

    /** A batch's hashes as of "now", keyed by its index, for planning a partial removal/recolor off-EDT. */
    fun snapshotBatchHashes(): Map<Int, Set<Hash>> =
        batches.withIndex().associate { (i, b) -> i to b.hashes }

    /**
     * Removes [hashesToRemove] from every batch. A batch fully covered by [hashesToRemove] is
     * dropped outright; a batch only partially covered keeps its remaining commits, using
     * [recomputedDiffs] (diff info recomputed for just those remaining commits, since a batch's
     * stored diff is a union across all its commits and can't otherwise be split back apart).
     */
    fun removeCommitsFromBatches(hashesToRemove: Set<Hash>, recomputedDiffs: Map<Int, Map<String, FileDiffInfo>>) {
        val newBatches = mutableListOf<HighlightBatch>()
        for ((i, batch) in batches.withIndex()) {
            val remaining = batch.hashes - hashesToRemove
            when {
                remaining.isEmpty() -> Unit // drop
                remaining.size == batch.hashes.size -> newBatches.add(batch)
                else -> {
                    val newDiff = recomputedDiffs[i] ?: batch.diffInfoByPath
                    newBatches.add(batch.copy(hashes = remaining, diffInfoByPath = newDiff))
                }
            }
        }
        batches.clear()
        batches.addAll(newBatches)
        reapplyAllEditors()
        repaintLogTable()
    }

    /** Called by [SelectedCommitLogHighlighterFactory] with the exact UI each highlighter instance belongs to. */
    fun registerLogUi(logUi: MainVcsLogUi) {
        registeredLogUis.removeAll { it.get() == null }
        registeredLogUis.add(WeakReference(logUi))
    }

    fun isShowOnlyHighlighted(): Boolean = showOnlyHighlighted

    fun allHighlightedHashes(): Set<Hash> = batches.flatMapTo(mutableSetOf()) { it.hashes }

    /** Toggles filtering every registered Git Log UI down to just the currently highlighted commits. */
    fun setShowOnlyHighlighted(enabled: Boolean) {
        showOnlyHighlighted = enabled
        applyHighlightFilter()
    }

    private fun allLogUis(): List<MainVcsLogUi> =
        (listOfNotNull(VcsProjectLog.getInstance(project).mainUi) + registeredLogUis.mapNotNull { it.get() }).distinct()

    private fun applyHighlightFilter() {
        val uis = allLogUis()
        if (!showOnlyHighlighted) {
            uis.forEach { it.filterUi.clearFilters() }
            return
        }
        val hashes = allHighlightedHashes().map { it.asString() }
        if (hashes.isEmpty()) {
            uis.forEach { it.filterUi.clearFilters() }
            return
        }
        val filters = VcsLogFilterObject.collection(VcsLogFilterObject.fromHashes(hashes))
        uis.forEach { it.filterUi.setFilters(filters) }
    }

    private fun repaintLogTable() {
        VcsProjectLog.getInstance(project).mainUi?.table?.repaint()
        for (ref in registeredLogUis) {
            ref.get()?.table?.repaint()
        }
        if (showOnlyHighlighted) {
            applyHighlightFilter()
        }
    }

    private fun reapplyAllEditors() {
        for (editor in FileEditorManager.getInstance(project).allEditors) {
            if (editor is TextEditor) {
                applyToFile(editor.file)
            }
        }
    }

    private fun applyToFile(file: VirtualFile) {
        removeHighlightsFor(file)

        val root = repoRoot ?: return
        val relativePath = relativePathOf(root, file) ?: return

        val lineColors = linkedMapOf<Int, HighlightColor>()
        val deletionInfo = linkedMapOf<Int, Pair<HighlightColor, DeletedLines>>()
        for (batch in batches) {
            val info = batch.diffInfoByPath[relativePath] ?: continue
            for (line in info.changedLines) {
                lineColors[line] = batch.color
            }
            for ((anchor, deleted) in info.deletionAnchors) {
                deletionInfo[anchor] = batch.color to deleted
            }
        }
        if (lineColors.isEmpty() && deletionInfo.isEmpty()) return

        val textEditor = FileEditorManager.getInstance(project).getEditors(file)
            .filterIsInstance<TextEditor>()
            .firstOrNull() ?: return

        val editor = textEditor.editor
        val document = editor.document
        val markupModel = editor.markupModel
        val tracked = mutableListOf<TrackedHighlight>()
        val inlays = mutableListOf<Inlay<*>>()
        val presentationFactory = PresentationFactory(editor)

        for ((lineNumber, color) in lineColors) {
            val zeroBasedLine = lineNumber - 1
            if (zeroBasedLine < 0 || zeroBasedLine >= document.lineCount) continue
            val startOffset = document.getLineStartOffset(zeroBasedLine)
            // Extending past the last character to include the line break (where there is one)
            // is what makes the background paint the full editor width instead of stopping at
            // the last character — EXACT_RANGE alone only covers the actual text.
            val endOffset = (document.getLineEndOffset(zeroBasedLine) + 1).coerceAtMost(document.textLength)

            val highlighter = markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,
                TextAttributes(null, color.toJBColor(), null, null, Font.PLAIN),
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighter.setErrorStripeMarkColor(color.toJBColor())
            highlighter.errorStripeTooltip = "Changed by a highlighted commit"
            tracked.add(TrackedHighlight(markupModel, highlighter))
        }

        val lineCount = document.lineCount
        for ((anchor, colorAndDeleted) in deletionInfo) {
            val (color, deleted) = colorAndDeleted
            val count = deleted.count
            val zeroBasedLine: Int
            val placement: SeparatorPlacement
            val showAbove: Boolean
            if (anchor <= 0) {
                zeroBasedLine = 0
                placement = SeparatorPlacement.TOP
                showAbove = true
            } else {
                zeroBasedLine = (anchor - 1).coerceAtMost(maxOf(lineCount - 1, 0))
                placement = SeparatorPlacement.BOTTOM
                showAbove = false
            }
            if (zeroBasedLine < 0 || zeroBasedLine >= lineCount) continue

            val separatorOffset = document.getLineStartOffset(zeroBasedLine)
            val highlighter = markupModel.addRangeHighlighter(
                separatorOffset,
                separatorOffset,
                HighlighterLayer.SELECTION - 1,
                TextAttributes(),
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighter.lineSeparatorColor = color.toJBColor()
            highlighter.lineSeparatorPlacement = placement
            val tooltipHtml = buildDeletionTooltip(deleted)
            highlighter.setErrorStripeMarkColor(color.toJBColor())
            highlighter.setThinErrorStripeMark(true)
            highlighter.errorStripeTooltip = tooltipHtml
            tracked.add(TrackedHighlight(markupModel, highlighter))

            val inlayOffset = if (showAbove) separatorOffset else document.getLineEndOffset(zeroBasedLine)
            val label = "$count ${if (count == 1) "line" else "lines"} deleted"
            val labelPresentation = presentationFactory.roundWithBackground(presentationFactory.smallText(label))
            val presentation = presentationFactory.withTooltip(tooltipHtml, labelPresentation)
            val inlay = editor.inlayModel.addBlockElement(
                inlayOffset,
                InlayProperties().showAbove(showAbove),
                PresentationRenderer(presentation)
            )
            if (inlay != null) {
                inlays.add(inlay)
            }
        }

        if (tracked.isNotEmpty()) {
            appliedHighlighters[file] = tracked
        }
        if (inlays.isNotEmpty()) {
            appliedInlays[file] = inlays
        }
    }

    private fun removeHighlightsFor(file: VirtualFile) {
        appliedHighlighters.remove(file)?.let { removeTracked(it) }
        appliedInlays.remove(file)?.let { disposeInlays(it) }
    }

    private fun removeTracked(tracked: List<TrackedHighlight>) {
        for (t in tracked) {
            if (t.highlighter.isValid) {
                t.markupModel.removeHighlighter(t.highlighter)
            }
        }
    }

    private fun disposeInlays(inlays: List<Inlay<*>>) {
        for (inlay in inlays) {
            if (inlay.isValid) {
                inlay.dispose()
            }
        }
    }

    private fun buildDeletionTooltip(deleted: DeletedLines): String {
        val maxLines = 40
        val shown = deleted.lines.take(maxLines)
        val body = shown.joinToString("\n") { escapeHtml(it) }
        val more = deleted.lines.size - shown.size
        val suffix = if (more > 0) "\n<i>… and $more more line${if (more == 1) "" else "s"}</i>" else ""
        return "<html><pre>$body</pre>$suffix</html>"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun relativePathOf(root: File, file: VirtualFile): String? {
        val filePath = File(file.path).canonicalFile.path
        val rootPath = root.canonicalFile.path
        if (!filePath.startsWith(rootPath)) return null
        return filePath.removePrefix(rootPath).trimStart('/', '\\').replace('\\', '/')
    }

    override fun dispose() {
        clearHighlights()
    }
}
