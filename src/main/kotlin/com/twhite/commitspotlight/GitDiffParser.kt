package com.twhite.commitspotlight

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/** The lines removed at a single deletion point: their count and actual text content. */
data class DeletedLines(val count: Int, val lines: List<String>)

/**
 * `deletionAnchors` maps the "new file" line number after which a pure deletion
 * hunk occurred (0 means "before line 1") to what was removed there, per git's
 * unified-diff convention for hunks with a zero new-side count. There's no
 * surviving line to background-tint, so callers draw a separator/label instead.
 *
 * `modificationAnchors` maps the first "new file" line number of a hunk that
 * replaced existing lines (both an old-side and new-side count > 0) to the
 * text that used to be there. Unlike deletions, every line in the hunk's new
 * range still exists and is tinted via [changedLines]; the anchor is just
 * where callers attach a "what this used to say" marker.
 */
data class FileDiffInfo(
    val changedLines: Set<Int> = emptySet(),
    val deletionAnchors: Map<Int, DeletedLines> = emptyMap(),
    val modificationAnchors: Map<Int, DeletedLines> = emptyMap()
)

/**
 * Shells out to plain `git`, rather than Git4Idea's Change/Revision APIs, so this
 * doesn't depend on internal VCS APIs that shift across platform versions.
 */
object GitDiffParser {

    private val LOG = Logger.getInstance(GitDiffParser::class.java)
    private val HUNK_HEADER = Regex("""^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    private val FULL_HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /**
     * Union of changed lines and deletions per repo-relative path, across all given commits —
     * remapped onto the file's *current* working-tree state (see [remapToCurrent]), so a commit
     * from well before HEAD still highlights the right lines rather than wherever its own
     * line numbers happen to land in a file that's since been reshaped around them.
     */
    fun changedLinesForCommits(repoRoot: File, commitHashes: List<String>): Map<String, FileDiffInfo> {
        val changed = mutableMapOf<String, MutableSet<Int>>()
        val deletions = mutableMapOf<String, MutableMap<Int, DeletedLines>>()
        val modifications = mutableMapOf<String, MutableMap<Int, DeletedLines>>()
        for (hash in commitHashes) {
            val patch = runGitShow(repoRoot, hash) ?: continue
            val parsed = remapToCurrent(repoRoot, hash, parsePatch(patch))
            for ((path, info) in parsed) {
                if (info.changedLines.isNotEmpty()) {
                    changed.getOrPut(path) { mutableSetOf() }.addAll(info.changedLines)
                }
                if (info.deletionAnchors.isNotEmpty()) {
                    mergeAnchors(deletions.getOrPut(path) { mutableMapOf() }, info.deletionAnchors)
                }
                if (info.modificationAnchors.isNotEmpty()) {
                    mergeAnchors(modifications.getOrPut(path) { mutableMapOf() }, info.modificationAnchors)
                }
            }
        }
        return (changed.keys + deletions.keys + modifications.keys).associateWith { path ->
            FileDiffInfo(changed[path] ?: emptySet(), deletions[path] ?: emptyMap(), modifications[path] ?: emptyMap())
        }
    }

    /**
     * Returns the subset of [commitHashes] that no longer resolve to an object in the repo —
     * e.g. dropped or rewritten by an interactive rebase. Used to clear stale highlights rather
     * than let them silently point at history that no longer exists.
     */
    fun missingCommits(repoRoot: File, commitHashes: List<String>): Set<String> {
        if (commitHashes.isEmpty()) return emptySet()
        return try {
            val process = ProcessBuilder("git", "cat-file", "--batch-check=%(objectname)")
                .directory(repoRoot)
                .redirectErrorStream(false)
                .start()
            // Written from a separate thread rather than inline: with enough hashes, git can fill
            // its stdout pipe before we finish writing stdin, and neither side would ever unblock
            // if both happened on this thread.
            val stdinWriter = Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        for (hash in commitHashes) {
                            writer.write(hash)
                            writer.newLine()
                        }
                    }
                } catch (_: Exception) {
                    // The waitFor()/exitValue() check below is what actually decides success —
                    // this just avoids crashing when the process has already exited early.
                }
            }
            stdinWriter.start()
            val outputLines = process.inputStream.bufferedReader().readLines()
            val error = process.errorStream.bufferedReader().readText()
            stdinWriter.join(TimeUnit.SECONDS.toMillis(15))
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LOG.warn("git cat-file timed out checking commit availability in $repoRoot")
                return emptySet()
            }
            if (process.exitValue() != 0) {
                LOG.warn("git cat-file failed checking commit availability in $repoRoot (exit ${process.exitValue()}): $error")
                return emptySet()
            }
            commitHashes.indices.mapNotNullTo(mutableSetOf()) { i ->
                val line = outputLines.getOrNull(i)
                if (line != null && line.endsWith(" missing")) commitHashes[i] else null
            }
        } catch (e: Exception) {
            LOG.warn("failed to check commit availability in $repoRoot", e)
            emptySet()
        }
    }

    private fun mergeAnchors(into: MutableMap<Int, DeletedLines>, from: Map<Int, DeletedLines>) {
        for ((anchor, lines) in from) {
            val existing = into[anchor]
            into[anchor] = DeletedLines(
                (existing?.count ?: 0) + lines.count,
                (existing?.lines ?: emptyList()) + lines.lines
            )
        }
    }

    private fun runGitShow(repoRoot: File, hash: String): String? {
        return try {
            val process = ProcessBuilder("git", "show", "--unified=0", "--format=", hash)
                .directory(repoRoot)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LOG.warn("git show timed out for commit $hash in $repoRoot")
                null
            } else if (process.exitValue() != 0) {
                LOG.warn("git show failed for commit $hash in $repoRoot (exit ${process.exitValue()}): $error")
                null
            } else {
                if (output.contains("@@@")) {
                    LOG.warn("commit $hash is a merge commit; its combined diff format isn't parsed, so it won't contribute any highlights")
                }
                output
            }
        } catch (e: Exception) {
            LOG.warn("failed to run git show for commit $hash in $repoRoot", e)
            null
        }
    }

    /** One `@@ -oldStart,oldCount +newStart,newCount @@` hunk from a *different* diff — see [remapToCurrent]. */
    private data class HunkRange(val oldStart: Int, val oldCount: Int, val newCount: Int)

    /**
     * Translates old-side line numbers to new-side ones using a sequence of [hunks] between the
     * two file versions being compared, treating everything outside a hunk as unchanged context
     * that simply shifts by the hunks before it.
     */
    private class LineMapper(hunks: List<HunkRange>) {
        private val sortedHunks = hunks.sortedBy { it.oldStart }

        /** Null means [oldLine] falls inside a range that was itself further changed — no single current line answers to it anymore. */
        fun mapOldToNew(oldLine: Int): Int? {
            var offset = 0
            for (hunk in sortedHunks) {
                if (hunk.oldCount == 0) {
                    // A pure insertion consumes no old lines — it sits *after* hunk.oldStart, per
                    // the same "anchor is the line before which/after which" convention this file
                    // already uses for deletionAnchors — so an old line at or before that anchor
                    // is untouched by it, and only strictly-later lines shift by its offset.
                    if (oldLine <= hunk.oldStart) return oldLine + offset
                } else {
                    if (oldLine < hunk.oldStart) return oldLine + offset
                    if (oldLine < hunk.oldStart + hunk.oldCount) return null
                }
                offset += hunk.newCount - hunk.oldCount
            }
            return oldLine + offset
        }
    }

    /**
     * [diffInfoByPath] describes each file as it looked immediately after [hash] was committed —
     * accurate then, but only coincidentally correct now if nothing since has shifted lines
     * above or within it. This remaps every line number in it onto the file's *current*
     * working-tree state by diffing [hash] straight against the working tree and walking that
     * diff's hunks: a line [hash] touched that a later commit (or an uncommitted edit) has since
     * further changed has no honest current position and is dropped, rather than risk painting
     * it somewhere wrong.
     */
    private fun remapToCurrent(repoRoot: File, hash: String, diffInfoByPath: Map<String, FileDiffInfo>): Map<String, FileDiffInfo> {
        if (diffInfoByPath.isEmpty()) return diffInfoByPath
        val patch = runGitDiffToWorkingTree(repoRoot, hash) ?: return emptyMap()
        val hunksByPath = parseHunkRangesByOldPath(patch)
        val result = mutableMapOf<String, FileDiffInfo>()
        for ((path, info) in diffInfoByPath) {
            // Not in the hash-vs-now diff at all means either "unchanged since hash" (still on
            // disk, so an identity mapping — no hunks — is correct) or "gone since hash" (can't
            // be mapped anywhere, so drop it rather than guess).
            val hunks = hunksByPath[path] ?: if (File(repoRoot, path).exists()) emptyList() else null
            if (hunks == null) continue
            val mapper = LineMapper(hunks)
            val changedLines = info.changedLines.mapNotNullTo(mutableSetOf(), mapper::mapOldToNew)
            val deletionAnchors = remapAnchors(info.deletionAnchors, mapper::mapOldToNew)
            val modificationAnchors = remapAnchors(info.modificationAnchors, mapper::mapOldToNew)
            if (changedLines.isNotEmpty() || deletionAnchors.isNotEmpty() || modificationAnchors.isNotEmpty()) {
                result[path] = FileDiffInfo(changedLines, deletionAnchors, modificationAnchors)
            }
        }
        return result
    }

    private fun remapAnchors(anchors: Map<Int, DeletedLines>, mapper: (Int) -> Int?): Map<Int, DeletedLines> {
        val result = mutableMapOf<Int, DeletedLines>()
        for ((anchor, deleted) in anchors) {
            val newAnchor = mapper(anchor) ?: continue
            mergeAnchors(result, mapOf(newAnchor to deleted))
        }
        return result
    }

    private fun runGitDiffToWorkingTree(repoRoot: File, hash: String): String? {
        return try {
            // No second revision: compares hash against the working tree (uncommitted edits
            // included), matching what's actually on disk — and so, modulo an unsaved buffer,
            // what's in the editor.
            val process = ProcessBuilder("git", "diff", "--unified=0", hash)
                .directory(repoRoot)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LOG.warn("git diff (remap) timed out for commit $hash in $repoRoot")
                null
            } else if (process.exitValue() != 0) {
                LOG.warn("git diff (remap) failed for commit $hash in $repoRoot (exit ${process.exitValue()}): $error")
                null
            } else {
                output
            }
        } catch (e: Exception) {
            LOG.warn("failed to run git diff (remap) for commit $hash in $repoRoot", e)
            null
        }
    }

    /** Hunk ranges keyed by the diff's *old*-side (`---`) path, since that's [hash]'s own path — the coordinate space [diffInfoByPath] is already in. */
    private fun parseHunkRangesByOldPath(patch: String): Map<String, List<HunkRange>> {
        val result = mutableMapOf<String, MutableList<HunkRange>>()
        var currentPath: String? = null
        for (line in patch.lineSequence()) {
            when {
                line.startsWith("--- ") -> {
                    val raw = line.removePrefix("--- ").trim()
                    currentPath = when {
                        raw == "/dev/null" -> null
                        raw.startsWith("a/") -> raw.removePrefix("a/")
                        else -> raw
                    }
                }
                line.startsWith("@@ ") -> {
                    val path = currentPath ?: continue
                    val match = FULL_HUNK_HEADER.find(line) ?: continue
                    val oldStart = match.groupValues[1].toIntOrNull() ?: continue
                    val oldCount = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                    val newCount = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                    result.getOrPut(path) { mutableListOf() }.add(HunkRange(oldStart, oldCount, newCount))
                }
            }
        }
        return result
    }

    private enum class PendingKind { DELETION, MODIFICATION }

    private fun parsePatch(patch: String): Map<String, FileDiffInfo> {
        val changed = mutableMapOf<String, MutableSet<Int>>()
        val deletions = mutableMapOf<String, MutableMap<Int, DeletedLines>>()
        val modifications = mutableMapOf<String, MutableMap<Int, DeletedLines>>()
        var currentPath: String? = null

        // A pure-deletion hunk (--unified=0) is a header followed immediately by exactly its
        // old-side count of '-'-prefixed lines and nothing else. A modification hunk (both an
        // old-side and new-side count > 0) is the same shape, just followed by '+' lines we
        // don't need to capture (their line numbers already came from the hunk header).
        var pendingKind: PendingKind? = null
        var pendingPath: String? = null
        var pendingAnchor: Int? = null
        var pendingRemaining = 0
        val pendingLines = mutableListOf<String>()

        fun flushPending() {
            val path = pendingPath
            val anchor = pendingAnchor
            if (path != null && anchor != null && pendingLines.isNotEmpty()) {
                val target = if (pendingKind == PendingKind.MODIFICATION) modifications else deletions
                val map = target.getOrPut(path) { mutableMapOf() }
                val existing = map[anchor]
                map[anchor] = DeletedLines(
                    (existing?.count ?: 0) + pendingLines.size,
                    (existing?.lines ?: emptyList()) + pendingLines
                )
            }
            pendingKind = null
            pendingPath = null
            pendingAnchor = null
            pendingRemaining = 0
            pendingLines.clear()
        }

        for (line in patch.lineSequence()) {
            if (pendingRemaining > 0 && line.startsWith("-") && !line.startsWith("--- ")) {
                pendingLines.add(line.removePrefix("-"))
                pendingRemaining--
                if (pendingRemaining == 0) flushPending()
                continue
            } else if (pendingRemaining > 0) {
                flushPending()
            }

            when {
                line.startsWith("+++ ") -> {
                    val raw = line.removePrefix("+++ ").trim()
                    currentPath = when {
                        raw == "/dev/null" -> null
                        raw.startsWith("b/") -> raw.removePrefix("b/")
                        else -> raw
                    }
                }
                line.startsWith("@@ ") -> {
                    val path = currentPath ?: continue
                    val match = HUNK_HEADER.find(line) ?: continue
                    val oldCount = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                    val newStart = match.groupValues[2].toIntOrNull() ?: continue
                    val newCount = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                    if (newCount == 0) {
                        if (oldCount > 0) {
                            pendingKind = PendingKind.DELETION
                            pendingPath = path
                            pendingAnchor = newStart
                            pendingRemaining = oldCount
                        }
                    } else {
                        val lines = changed.getOrPut(path) { mutableSetOf() }
                        for (i in 0 until newCount) {
                            lines.add(newStart + i)
                        }
                        if (oldCount > 0) {
                            pendingKind = PendingKind.MODIFICATION
                            pendingPath = path
                            pendingAnchor = newStart
                            pendingRemaining = oldCount
                        }
                    }
                }
            }
        }
        flushPending()

        return (changed.keys + deletions.keys + modifications.keys).associateWith { path ->
            FileDiffInfo(changed[path] ?: emptySet(), deletions[path] ?: emptyMap(), modifications[path] ?: emptyMap())
        }
    }
}
