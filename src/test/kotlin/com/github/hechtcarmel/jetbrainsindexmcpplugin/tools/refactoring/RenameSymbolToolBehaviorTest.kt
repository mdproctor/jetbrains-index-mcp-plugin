package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

class RenameSymbolToolBehaviorTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file ${path}"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    private fun readProjectFileVfs(relativePath: String): String {
        val basePath = requireNotNull(project.basePath)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
            ?: return Files.readString(Path.of(basePath, relativePath))
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        return doc?.text ?: String(vf.contentsToByteArray())
    }

    private fun assumeJsTsAvailable() {
        Assume.assumeTrue("JavaScript plugin not available", PluginDetectors.javaScript.isAvailable)
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSNamedElement")
        } catch (_: ClassNotFoundException) {
            Assume.assumeTrue("JavaScript PSI classes unavailable", false)
        }
    }

    fun testJsTsFileRenameRetargetsImportsThroughSemanticHooks() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile(
            "src/utils/leaf.ts",
            "export const leafThing = 1;\n"
        )
        writeProjectFile(
            "src/app.ts",
            """
            import { leafThing } from './utils/leaf';
            import './utils/leaf';
            export { leafThing } from './utils/leaf';
            const lazy = import('./utils/leaf');
            const nested = {
              leaf: leafThing,
            };
            """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/utils/leaf.ts")
            put("targetType", "file")
            put("line", 0)
            put("column", 0)
            put("newName", "leaf-renamed.ts")
        })

        assertFalse("JS/TS file rename should succeed", result.isError)
        val payload = json.decodeFromString<RefactoringResult>((result.content.single() as ContentBlock.Text).text)

        val basePath = requireNotNull(project.basePath)
        assertTrue(Files.exists(Path.of(basePath, "src/utils/leaf-renamed.ts")))
        assertFalse(Files.exists(Path.of(basePath, "src/utils/leaf.ts")))

        val appText = Files.readString(Path.of(basePath, "src/app.ts"))
        assertTrue(appText.contains("import { leafThing } from './utils/leaf-renamed';"))
        assertTrue(appText.contains("import './utils/leaf-renamed';"))
        assertTrue(appText.contains("export { leafThing } from './utils/leaf-renamed';"))
        assertTrue(appText.contains("import('./utils/leaf-renamed')"))
        assertFalse(appText.contains("./utils/leaf'"))
        assertFalse(appText.contains("./utils/leaf\""))
        assertTrue(payload.affectedFiles.contains("src/utils/leaf-renamed.ts"))
        assertTrue(payload.affectedFiles.contains("src/app.ts"))
        assertEquals(payload.affectedFiles.size, payload.changesCount)
        assertNull(payload.unretargetedImporters)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testJsTsSameDirectoryFileRenameDoesNotRetargetDirectorySegment() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile(
            "src/jobs/generate-recurring-gastos.logic.ts",
            "export const calculateOverduePeriods = () => 0;\nexport const MAX_OVERDUE_ITERATIONS = 24;\n"
        )
        writeProjectFile(
            "src/jobs/generate-recurring-gastos.ts",
            """
            import { calculateOverduePeriods, MAX_OVERDUE_ITERATIONS } from './generate-recurring-gastos.logic';
            export { calculateOverduePeriods, MAX_OVERDUE_ITERATIONS } from './generate-recurring-gastos.logic';
            """.trimIndent()
        )
        writeProjectFile(
            "tests/generate-recurring-gastos.test.ts",
            """
            import { calculateOverduePeriods, MAX_OVERDUE_ITERATIONS } from '../src/jobs/generate-recurring-gastos.logic.ts';
            void calculateOverduePeriods;
            void MAX_OVERDUE_ITERATIONS;
            """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/jobs/generate-recurring-gastos.logic.ts")
            put("targetType", "file")
            put("newName", "generate-recurring-gastos.logic_smoke.ts")
        })

        assertFalse("JS/TS same-directory file rename should succeed", result.isError)
        val payload = json.decodeFromString<RefactoringResult>((result.content.single() as ContentBlock.Text).text)

        val basePath = requireNotNull(project.basePath)
        val jobText = Files.readString(Path.of(basePath, "src/jobs/generate-recurring-gastos.ts"))
        val testText = Files.readString(Path.of(basePath, "tests/generate-recurring-gastos.test.ts"))

        assertTrue(jobText.contains("./generate-recurring-gastos.logic_smoke"))
        assertTrue(testText.contains("../src/jobs/generate-recurring-gastos.logic_smoke.ts"))
        assertFalse(jobText.contains("././generate-recurring-gastos.logic_smoke"))
        assertFalse(testText.contains("../src/jobs/./generate-recurring-gastos.logic_smoke.ts"))
        assertTrue(payload.affectedFiles.contains("src/jobs/generate-recurring-gastos.ts"))
        assertTrue(payload.affectedFiles.contains("tests/generate-recurring-gastos.test.ts"))
        assertNull(payload.unretargetedImporters)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testJsTsFileRenameDoesNotReportFalseUnretargetedImportersWhenPlatformUpdatesImporters() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile(
            "src/frontend/src/__mcp_pr175_fixture__/config.ts",
            "export const loadConfig = () => ({});\n"
        )
        writeProjectFile(
            "src/frontend/src/__mcp_pr175_fixture__/importOnly.ts",
            """
            import { loadConfig } from "./config";

            export const importedName = loadConfig;
            """.trimIndent()
        )
        writeProjectFile(
            "src/frontend/src/__mcp_pr175_fixture__/index.ts",
            """
            export { loadConfig } from "./config";
            export * from "./config";
            """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/frontend/src/__mcp_pr175_fixture__/config.ts")
            put("targetType", "file")
            put("newName", "configRenamed.ts")
        })

        assertFalse("JS/TS file rename should succeed", result.isError)
        val payload = json.decodeFromString<RefactoringResult>((result.content.single() as ContentBlock.Text).text)

        val basePath = requireNotNull(project.basePath)
        val importOnlyText = Files.readString(
            Path.of(basePath, "src/frontend/src/__mcp_pr175_fixture__/importOnly.ts")
        )
        val indexText = Files.readString(
            Path.of(basePath, "src/frontend/src/__mcp_pr175_fixture__/index.ts")
        )

        assertTrue(importOnlyText.contains("\"./configRenamed\""))
        assertTrue(indexText.contains("\"./configRenamed\""))
        assertFalse(importOnlyText.contains("\"./config\""))
        assertFalse(indexText.contains("\"./config\""))
        assertNull(payload.warnings)
        assertNull(payload.unretargetedImporters)
        assertFalse(payload.message.contains("could not be auto-retargeted"))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testExplicitFileRenameIgnoresMalformedCoordinatesDuringFullToolExecution() = runBlocking {
        writeProjectFile(
            "docs/readme.txt",
            "Rename me through file mode.\n"
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "docs/readme.txt")
            put("targetType", "file")
            put("line", JsonPrimitive("not-a-number"))
            put("column", JsonPrimitive("still-not-a-number"))
            put("newName", "readme-renamed.txt")
        })

        assertFalse("Explicit file rename should ignore malformed line/column values: ${result.content}", result.isError)

        val basePath = requireNotNull(project.basePath)
        assertFalse(Files.exists(Path.of(basePath, "docs/readme.txt")))
        assertTrue(Files.exists(Path.of(basePath, "docs/readme-renamed.txt")))
    }

    // ── Java: symbol rename ──

    fun testJavaRenameMethodUpdatesCallSitesWithinFile() = runBlocking {
        writeProjectFile("src/UserService.java", """
            public class UserService {
                public String getDisplayName() {
                    return "name";
                }
                public String show() {
                    return getDisplayName();
                }
            }
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/UserService.java")
            put("line", 2)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertFalse("Java method rename should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val text = readProjectFileVfs("src/UserService.java")
        assertTrue("Method should be renamed in declaration: $text", text.contains("getFullName"))
    }

    fun testJavaRenameFieldUpdatesReferencesWithinFile() = runBlocking {
        writeProjectFile("src/FieldRenameTarget.java", """
            public class FieldRenameTarget {
                public int count = 0;
                public void increment() {
                    count = count + 1;
                }
            }
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/FieldRenameTarget.java")
            put("line", 2)
            put("column", 16)
            put("newName", "total")
        })

        val payload = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertFalse("Java field rename should succeed: $payload", result.isError)
        val text = readProjectFileVfs("src/FieldRenameTarget.java")
        assertTrue("Field declaration should use new name: $text", text.contains("int total"))
    }

    fun testJavaRenameClassRenamesFile() = runBlocking {
        writeProjectFile("src/OldName.java", """
            public class OldName {
                public void doWork() {}
            }
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/OldName.java")
            put("line", 1)
            put("column", 14)
            put("newName", "NewName")
        })

        assertFalse("Java class rename should succeed", result.isError)
        val text = readProjectFileVfs("src/NewName.java")
        assertTrue("Class declaration updated: $text", text.contains("class NewName"))
    }

    fun testJavaRenameParameterUpdatesUsagesInBody() = runBlocking {
        writeProjectFile("src/Processor.java", """
            public class Processor {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Processor.java")
            put("line", 2)
            put("column", 34)
            put("newName", "rawValue")
        })

        assertFalse("Java parameter rename should succeed", result.isError)
        val text = readProjectFileVfs("src/Processor.java")
        assertTrue("Parameter should be renamed in signature: $text", text.contains("String rawValue"))
        assertTrue("Usage in body should be updated", text.contains("rawValue.trim()"))
    }

    // ── TypeScript: symbol rename ──

    fun testTypeScriptRenameMethodUpdatesImporters() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile("src/service.ts", """
            export class UserService {
                getDisplayName(): string {
                    return "name";
                }
            }
        """.trimIndent())
        writeProjectFile("src/app.ts", """
            import { UserService } from './service';
            const svc = new UserService();
            console.log(svc.getDisplayName());
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/service.ts")
            put("line", 2)
            put("column", 5)
            put("newName", "getFullName")
        })

        assertFalse("TS method rename should succeed", result.isError)
        val basePath = requireNotNull(project.basePath)
        val serviceText = Files.readString(Path.of(basePath, "src/service.ts"))
        val appText = Files.readString(Path.of(basePath, "src/app.ts"))
        assertTrue("Method renamed in declaration", serviceText.contains("getFullName"))
        assertFalse("Old name gone from declaration", serviceText.contains("getDisplayName"))
        assertTrue("Call site updated", appText.contains("getFullName"))
    }

    fun testTypeScriptRenameFieldUpdatesReferences() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile("src/state.ts", """
            export class AppState {
                readonly isLoading: boolean = false;
                check(): boolean {
                    return this.isLoading;
                }
            }
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/state.ts")
            put("line", 2)
            put("column", 14)
            put("newName", "isPending")
        })

        assertFalse("TS field rename should succeed", result.isError)
        val basePath = requireNotNull(project.basePath)
        val text = Files.readString(Path.of(basePath, "src/state.ts"))
        assertTrue("Field renamed", text.contains("isPending"))
        assertTrue("Reference updated", text.contains("this.isPending"))
        assertFalse("Old name gone", text.contains("isLoading"))
    }

    fun testTypeScriptRenameClassUpdatesImports() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile("src/old-model.ts", """
            export class OldModel {
                id: string = "";
            }
        """.trimIndent())
        writeProjectFile("src/consumer.ts", """
            import { OldModel } from './old-model';
            const m: OldModel = new OldModel();
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/old-model.ts")
            put("line", 1)
            put("column", 14)
            put("newName", "NewModel")
        })

        assertFalse("TS class rename should succeed", result.isError)
        val basePath = requireNotNull(project.basePath)
        val consumerText = Files.readString(Path.of(basePath, "src/consumer.ts"))
        assertTrue("Import updated", consumerText.contains("NewModel"))
        assertTrue("Type reference updated", consumerText.contains("const m: NewModel"))
        assertFalse("Old class name gone", consumerText.contains("OldModel"))
    }

    fun testTypeScriptRenameParameterUpdatesBody() = runBlocking {
        assumeJsTsAvailable()

        writeProjectFile("src/utils.ts", """
            export function format(input: string): string {
                return input.trim().toLowerCase();
            }
        """.trimIndent())

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/utils.ts")
            put("line", 1)
            put("column", 24)
            put("newName", "rawValue")
        })

        assertFalse("TS parameter rename should succeed", result.isError)
        val basePath = requireNotNull(project.basePath)
        val text = Files.readString(Path.of(basePath, "src/utils.ts"))
        assertTrue("Parameter renamed in signature", text.contains("rawValue: string"))
        assertTrue("Usage in body updated", text.contains("rawValue.trim()"))
        assertFalse("Old parameter name gone", text.contains("input"))
    }
}
