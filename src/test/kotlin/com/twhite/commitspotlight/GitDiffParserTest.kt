package com.twhite.commitspotlight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Exercises the real `git` binary against throwaway repos rather than hand-crafting diff text,
 * so these tests catch mismatches with git's actual output format, not just our assumptions.
 */
class GitDiffParserTest {

    @TempDir
    lateinit var repo: File

    @BeforeEach
    fun setUp() {
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")
    }

    @Test
    fun `added lines are reported as changed lines`() {
        writeFile("a.txt", "one\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add lines")

        val result = GitDiffParser.changedLinesForCommits(repo, listOf(hash))

        assertEquals(setOf(2, 3), result["a.txt"]?.changedLines)
        assertTrue(result["a.txt"]?.deletionAnchors?.isEmpty() ?: true)
    }

    @Test
    fun `pure deletion in the middle records anchor, count, and removed text`() {
        writeFile("a.txt", "one\ntwo\nthree\nfour\n")
        commitAll("initial")
        writeFile("a.txt", "one\nfour\n")
        val hash = commitAll("delete middle")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!
        val deletion = info.deletionAnchors[1]

        assertTrue(info.changedLines.isEmpty())
        assertNotNull(deletion)
        assertEquals(2, deletion!!.count)
        assertEquals(listOf("two", "three"), deletion.lines)
    }

    @Test
    fun `a deleted line starting with two dashes and a space is captured, not mistaken for a diff file header`() {
        // Regression test: a removed line whose own text starts with "-- " (a common line-comment
        // marker in SQL/Lua/Haskell/Ada/AppleScript) renders in the diff as "--- <text>", which
        // collides with the unrelated "--- <path>" file-header prefix unless the parser only
        // checks for that header outside of an active hunk body.
        writeFile("a.txt", "one\n-- comment\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "one\nthree\n")
        val hash = commitAll("delete comment line")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!
        val deletion = info.deletionAnchors[1]

        assertNotNull(deletion)
        assertEquals(listOf("-- comment"), deletion!!.lines)
    }

    @Test
    fun `a modified line whose old text starts with two dashes and a space is captured`() {
        writeFile("a.txt", "one\n-- comment\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "one\n-- updated comment\nthree\n")
        val hash = commitAll("modify comment line")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!
        val modification = info.modificationAnchors[2]

        assertEquals(setOf(2), info.changedLines)
        assertNotNull(modification)
        assertEquals(listOf("-- comment"), modification!!.lines)
    }

    @Test
    fun `deletion at start of file uses anchor zero`() {
        writeFile("a.txt", "one\ntwo\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "two\nthree\n")
        val hash = commitAll("delete first line")

        val deletion = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!.deletionAnchors[0]

        assertNotNull(deletion)
        assertEquals(listOf("one"), deletion!!.lines)
    }

    @Test
    fun `deletion at end of file anchors to the last surviving line`() {
        writeFile("a.txt", "one\ntwo\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\n")
        val hash = commitAll("delete last line")

        val deletion = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!.deletionAnchors[2]

        assertNotNull(deletion)
        assertEquals(listOf("three"), deletion!!.lines)
    }

    @Test
    fun `modifying a line is reported via changedLines, not deletionAnchors`() {
        writeFile("a.txt", "one\ntwo\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "one\nTWO\nthree\n")
        val hash = commitAll("modify line 2")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!

        assertEquals(setOf(2), info.changedLines)
        assertTrue(info.deletionAnchors.isEmpty())
    }

    @Test
    fun `modifying a line records the old text at the changed line's number`() {
        writeFile("a.txt", "one\ntwo\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "one\nTWO\nthree\n")
        val hash = commitAll("modify line 2")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!
        val modification = info.modificationAnchors[2]

        assertNotNull(modification)
        assertEquals(1, modification!!.count)
        assertEquals(listOf("two"), modification.lines)
    }

    @Test
    fun `a hunk collapsing multiple old lines into fewer new ones anchors old text to the first new line`() {
        writeFile("a.txt", "one\ntwo\nthree\nfour\n")
        commitAll("initial")
        writeFile("a.txt", "one\nTWO-THREE\nfour\n")
        val hash = commitAll("collapse two lines into one")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!
        val modification = info.modificationAnchors[2]

        assertEquals(setOf(2), info.changedLines)
        assertNotNull(modification)
        assertEquals(2, modification!!.count)
        assertEquals(listOf("two", "three"), modification.lines)
    }

    @Test
    fun `a pure addition has no modification anchor`() {
        writeFile("a.txt", "one\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add lines")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!

        assertTrue(info.modificationAnchors.isEmpty())
    }

    @Test
    fun `multiple commits are unioned and deletion counts summed`() {
        writeFile("a.txt", "one\ntwo\nthree\nfour\nfive\n")
        commitAll("initial")
        writeFile("a.txt", "one\nthree\nfour\nfive\n")
        val hash1 = commitAll("delete two")
        writeFile("a.txt", "one\nthree\nfour\nfive\nsix\n")
        val hash2 = commitAll("add six")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash1, hash2))["a.txt"]!!

        assertEquals(setOf(5), info.changedLines)
        assertEquals(1, info.deletionAnchors.values.sumOf { it.count })
    }

    @Test
    fun `touching multiple files in one commit reports each separately`() {
        writeFile("a.txt", "a1\n")
        writeFile("b.txt", "b1\n")
        commitAll("initial")
        writeFile("a.txt", "a1\na2\n")
        writeFile("b.txt", "b1\nb2\n")
        val hash = commitAll("touch both")

        val result = GitDiffParser.changedLinesForCommits(repo, listOf(hash))

        assertEquals(setOf(2), result["a.txt"]?.changedLines)
        assertEquals(setOf(2), result["b.txt"]?.changedLines)
    }

    @Test
    fun `unresolvable commit hash is skipped without throwing`() {
        writeFile("a.txt", "one\n")
        commitAll("initial")

        val result = GitDiffParser.changedLinesForCommits(repo, listOf("0".repeat(40)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty commit list returns empty map`() {
        assertTrue(GitDiffParser.changedLinesForCommits(repo, emptyList()).isEmpty())
    }

    // --- Remapping onto the file's *current* state (not just the commit's own diff) ---

    @Test
    fun `highlighted lines shift when a later commit inserts lines above them`() {
        writeFile("a.txt", "one\ntwo\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add three")
        writeFile("a.txt", "zero\none\ntwo\nthree\n")
        commitAll("insert zero at the top")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!

        // "three" was added at line 3 by `hash`; the later insertion at the top pushes it to 4.
        assertEquals(setOf(4), info.changedLines)
    }

    @Test
    fun `a line is dropped once a later commit further changes it`() {
        writeFile("a.txt", "one\ntwo\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add three")
        writeFile("a.txt", "one\ntwo\nTHREE\n")
        commitAll("someone else edits three")

        val result = GitDiffParser.changedLinesForCommits(repo, listOf(hash))

        // Nothing left to attribute to `hash` at all, so "a.txt" isn't a key in the result —
        // same as any other path with no surviving highlight-worthy content.
        assertTrue(result["a.txt"]?.changedLines.isNullOrEmpty())
    }

    @Test
    fun `a line dropped by later history is not highlighted at all`() {
        writeFile("a.txt", "one\ntwo\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add three")
        writeFile("a.txt", "one\ntwo\n")
        commitAll("someone else removes three again")

        val result = GitDiffParser.changedLinesForCommits(repo, listOf(hash))

        assertTrue(result["a.txt"]?.changedLines.isNullOrEmpty())
    }

    @Test
    fun `deletion anchor shifts when a later commit inserts lines above it`() {
        writeFile("a.txt", "one\ntwo\nthree\n")
        commitAll("initial")
        writeFile("a.txt", "one\nthree\n")
        val hash = commitAll("delete two")
        writeFile("a.txt", "zero\none\nthree\n")
        commitAll("insert zero at the top")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!

        assertTrue(info.changedLines.isEmpty())
        assertEquals(listOf("two"), info.deletionAnchors[2]?.lines)
    }

    @Test
    fun `an unchanged file since the highlighted commit needs no remapping`() {
        writeFile("a.txt", "one\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add lines")
        // No further commits touch a.txt — current state is exactly what `hash` produced.

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!

        assertEquals(setOf(2, 3), info.changedLines)
    }

    @Test
    fun `a line immediately followed by a later insertion is not swallowed by that insertion's offset`() {
        // Regression test for a boundary bug: a pure-insertion hunk's anchor line (its oldCount
        // is 0, so it consumes no old lines) was incorrectly treated as falling *inside* that
        // hunk, pulling in its offset instead of just the hunks before it — e.g. a block ending
        // exactly where a later commit's insertion begins would map to a wildly wrong line
        // number, landing well past where that later insertion's content ends.
        writeFile("a.txt", "one\ntwo\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\nfour\n")
        val hash = commitAll("add three and four")
        // Insert 20 lines immediately after "four" (i.e. right after the block `hash` added).
        val laterLines = (1..20).joinToString("") { "later-$it\n" }
        writeFile("a.txt", "one\ntwo\nthree\nfour\n$laterLines")
        commitAll("insert a large block right after")

        val info = GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]!!

        // "three" and "four" keep their original positions — the insertion coming *after* them
        // must not shift them forward by its own line count.
        assertEquals(setOf(3, 4), info.changedLines)
    }

    // --- filesTouchedByCommits: raw file list, independent of remapToCurrent's filtering ---

    @Test
    fun `filesTouchedByCommits includes a file even after a later commit fully overwrites the change`() {
        // Same history as "a line is dropped once a later commit further changes it", where
        // changedLinesForCommits (correctly, for highlighting) drops "a.txt" entirely — but
        // "Open All Files in Commit" still needs to open it, since `hash` did touch it.
        writeFile("a.txt", "one\ntwo\n")
        commitAll("initial")
        writeFile("a.txt", "one\ntwo\nthree\n")
        val hash = commitAll("add three")
        writeFile("a.txt", "one\ntwo\nTHREE\n")
        commitAll("someone else edits three")

        assertEquals(emptySet<Int>(), GitDiffParser.changedLinesForCommits(repo, listOf(hash))["a.txt"]?.changedLines ?: emptySet<Int>())
        assertEquals(setOf("a.txt"), GitDiffParser.filesTouchedByCommits(repo, listOf(hash)))
    }

    @Test
    fun `filesTouchedByCommits unions paths across multiple commits`() {
        writeFile("a.txt", "a1\n")
        writeFile("b.txt", "b1\n")
        commitAll("initial")
        writeFile("a.txt", "a1\na2\n")
        val hash1 = commitAll("touch a")
        writeFile("b.txt", "b1\nb2\n")
        val hash2 = commitAll("touch b")

        assertEquals(setOf("a.txt", "b.txt"), GitDiffParser.filesTouchedByCommits(repo, listOf(hash1, hash2)))
    }

    @Test
    fun `filesTouchedByCommits returns empty set for an unresolvable commit hash`() {
        writeFile("a.txt", "one\n")
        commitAll("initial")

        assertTrue(GitDiffParser.filesTouchedByCommits(repo, listOf("0".repeat(40))).isEmpty())
    }

    private fun writeFile(relativePath: String, content: String) {
        File(repo, relativePath).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun commitAll(message: String): String {
        runGit(repo, "add", "-A")
        runGit(repo, "commit", "-q", "-m", message)
        return runGit(repo, "rev-parse", "HEAD").trim()
    }

    private fun runGit(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
        return output
    }
}
