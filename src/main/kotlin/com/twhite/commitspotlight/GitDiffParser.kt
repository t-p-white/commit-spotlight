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
 */
data class FileDiffInfo(
    val changedLines: Set<Int> = emptySet(),
    val deletionAnchors: Map<Int, DeletedLines> = emptyMap()
)

/**
 * Shells out to plain `git`, rather than Git4Idea's Change/Revision APIs, so this
 * doesn't depend on internal VCS APIs that shift across platform versions.
 */
object GitDiffParser {

    private val LOG = Logger.getInstance(GitDiffParser::class.java)
    private val HUNK_HEADER = Regex("""^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /** Union of changed lines and deletions per repo-relative path, across all given commits. */
    fun changedLinesForCommits(repoRoot: File, commitHashes: List<String>): Map<String, FileDiffInfo> {
        val changed = mutableMapOf<String, MutableSet<Int>>()
        val deletions = mutableMapOf<String, MutableMap<Int, DeletedLines>>()
        for (hash in commitHashes) {
            val patch = runGitShow(repoRoot, hash) ?: continue
            val parsed = parsePatch(patch)
            for ((path, info) in parsed) {
                if (info.changedLines.isNotEmpty()) {
                    changed.getOrPut(path) { mutableSetOf() }.addAll(info.changedLines)
                }
                if (info.deletionAnchors.isNotEmpty()) {
                    val map = deletions.getOrPut(path) { mutableMapOf() }
                    for ((anchor, deleted) in info.deletionAnchors) {
                        val existing = map[anchor]
                        map[anchor] = DeletedLines(
                            (existing?.count ?: 0) + deleted.count,
                            (existing?.lines ?: emptyList()) + deleted.lines
                        )
                    }
                }
            }
        }
        return (changed.keys + deletions.keys).associateWith { path ->
            FileDiffInfo(changed[path] ?: emptySet(), deletions[path] ?: emptyMap())
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

    private fun parsePatch(patch: String): Map<String, FileDiffInfo> {
        val changed = mutableMapOf<String, MutableSet<Int>>()
        val deletions = mutableMapOf<String, MutableMap<Int, DeletedLines>>()
        var currentPath: String? = null

        // A pure-deletion hunk (--unified=0) is a header followed immediately by
        // exactly its old-side count of '-'-prefixed lines and nothing else.
        var pendingPath: String? = null
        var pendingAnchor: Int? = null
        var pendingRemaining = 0
        val pendingLines = mutableListOf<String>()

        fun flushPending() {
            val path = pendingPath
            val anchor = pendingAnchor
            if (path != null && anchor != null && pendingLines.isNotEmpty()) {
                val map = deletions.getOrPut(path) { mutableMapOf() }
                val existing = map[anchor]
                map[anchor] = DeletedLines(
                    (existing?.count ?: 0) + pendingLines.size,
                    (existing?.lines ?: emptyList()) + pendingLines
                )
            }
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
                            pendingPath = path
                            pendingAnchor = newStart
                            pendingRemaining = oldCount
                        }
                    } else {
                        val lines = changed.getOrPut(path) { mutableSetOf() }
                        for (i in 0 until newCount) {
                            lines.add(newStart + i)
                        }
                    }
                }
            }
        }
        flushPending()

        return (changed.keys + deletions.keys).associateWith { path ->
            FileDiffInfo(changed[path] ?: emptySet(), deletions[path] ?: emptyMap())
        }
    }
}
