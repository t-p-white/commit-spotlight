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
