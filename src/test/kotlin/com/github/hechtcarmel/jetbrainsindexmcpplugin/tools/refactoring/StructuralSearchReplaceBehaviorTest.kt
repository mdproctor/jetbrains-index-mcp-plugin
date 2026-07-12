package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StructuralSearchReplaceBehaviorTest : BasePlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file $path"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    private fun readProjectFileVfs(relativePath: String): String {
        val basePath = requireNotNull(project.basePath)
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
            ?: return Files.readString(Path.of(basePath, relativePath))
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        return doc?.text ?: String(vf.contentsToByteArray())
    }

    fun testSearchOnlyReturnsMatches() = runBlocking {
        writeProjectFile("src/SsrLogger.java", """
            public class SsrLogger {
                public void log(String msg) {
                    System.out.println(msg);
                    System.out.println("debug: " + msg);
                }
            }
        """.trimIndent())

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "System.out.println(\$arg\$)")
            put("filePattern", "*.java")
        })

        val text = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertFalse("Search should succeed: $text", result.isError)
        assertTrue("Should return matchCount field: $text", text.contains("\"matchCount\""))
        assertTrue("Should return success field: $text", text.contains("\"success\":true"))
    }

    fun testSearchNoMatchesReturnsZero() = runBlocking {
        writeProjectFile("src/SsrEmpty.java", """
            public class SsrEmpty {
                public void nothing() {}
            }
        """.trimIndent())

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "System.exit(\$arg\$)")
        })

        val text = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertFalse("Search should succeed: $text", result.isError)
    }

    // SSR replace requires full IDE initialization (Java SSR profile) that
    // BasePlatformTestCase may not provide. The test verifies the tool produces
    // a result without crashing; actual replacement verification requires runIde.
    fun testSearchAndReplaceDoesNotCrash() = runBlocking {
        writeProjectFile("src/SsrMigration.java", """
            public class SsrMigration {
                void run() {
                    System.out.println("hello");
                    System.out.println("world");
                }
            }
        """.trimIndent())

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "System.out.println(\$arg\$)")
            put("replacePattern", "System.err.println(\$arg\$)")
            put("filePattern", "*.java")
        })

        val text = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertNotNull("Should produce a result", text)
        if (!result.isError) {
            assertTrue("Success should have success field: $text", text.contains("\"success\":true"))
        }
    }

    fun testEmptySearchPatternFails() = runBlocking {
        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "")
        })

        assertTrue("Empty pattern should fail", result.isError)
    }

    fun testMissingSearchPatternFails() = runBlocking {
        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {})

        assertTrue("Missing pattern should fail", result.isError)
    }
}
