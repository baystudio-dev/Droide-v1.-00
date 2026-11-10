package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectPythonConfigurationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun absentConfigurationDoesNotDiscoverOrCreateAnEnvironment() {
        val root = temp.newFolder("workspace")
        assertNull(ProjectPythonConfiguration.load(root))
        assertTrue(root.listFiles()!!.isEmpty())
        File(root, ".venv/bin").mkdirs()
        File(root, ".venv/bin/python").writeText("not selected")
        File(root, "pyproject.toml").writeText("[project]\nname = 'ignored'\n")
        File(root, ".droide").mkdir()
        File(root, ".droide/tasks.json").writeText("unrelated")
        assertNull(ProjectPythonConfiguration.load(root))
        assertFalse(File(root, ProjectPythonConfiguration.CONFIG_PATH).exists())
        assertEquals("not selected", File(root, ".venv/bin/python").readText())
    }

    @Test fun jsoncCommentsAndTrailingCommaAreSupportedAndExactBytesAreHashed() {
        val text = """
            {
              // The interpreter already exists in the Ubuntu project.
              "interpreter": ".venv/bin/python", /* explicit */
            }
        """.trimIndent() + "\r\n"
        val config = configFile(text)
        val selection = ProjectPythonConfiguration.load(temp.root)!!
        assertEquals("./.venv/bin/python", selection.interpreter)
        assertEquals(sha256(config.readBytes()), selection.configSha256)
        assertEquals(listOf("./.venv/bin/python", "src/main.py"), selection.runArgv("src/main.py"))
        assertEquals(listOf("./.venv/bin/python", "-m", "pytest"), selection.pytestArgv())
    }

    @Test fun commentsAndWhitespaceChangeApprovalIdentityEvenWithTheSameInterpreter() {
        val file = configFile("{\"interpreter\":\"python3\"}")
        val first = ProjectPythonConfiguration.load(temp.root)!!
        file.appendText(" // reviewed again\n")
        val second = ProjectPythonConfiguration.load(temp.root)!!
        assertEquals(first.interpreter, second.interpreter)
        assertNotEquals(first.configSha256, second.configSha256)
        assertEquals(sha256(file.readBytes()), second.configSha256)
    }

    @Test fun safeBareCommandsAndGuestAbsolutePathsStayLiteral() {
        for (value in listOf("python3", "python3.12", "pypy3", "python-custom", "_python", "/usr/bin/python3", "/opt/my python/bin/python")) {
            selected(value).let {
                assertEquals(value, it.interpreter)
                assertEquals(listOf(value, "-m", "pytest"), it.pytestArgv())
            }
        }
    }

    @Test fun relativePathsUseProjectCwdAndPreserveOneArgumentWithoutExpansion() {
        val paths = listOf(".venv/bin/python", "venv with spaces/bin/python", "env;touch sentinel/bin/python",
            "env\$(touch sentinel)/bin/python", "${'$'}HOME/bin/python", "~/bin/python", "env'\"`*\\/bin/python")
        for (value in paths) {
            val selection = selected(value)
            assertEquals("./$value", selection.interpreter)
            assertEquals(listOf("./$value", "src/a ' ; \$(touch sentinel).py"), selection.runArgv("src/a ' ; \$(touch sentinel).py"))
            assertEquals(3, selection.pytestArgv().size)
        }
        assertEquals("./.venv/bin/python", selected("./.venv/bin/python").interpreter)
        assertFalse(File(temp.root, "sentinel").exists())
    }

    @Test fun leadingHyphenInSourceNameCannotBecomeAPythonOption() {
        assertEquals(listOf("python3", "./-c.py"), selected("python3").runArgv("-c.py"))
    }

    @Test fun interpreterIsNeverResolvedOrRequiredToExistOnTheHost() {
        val selectedPath = File(temp.root, ".venv/bin/python")
        selectedPath.parentFile.mkdirs()
        Files.createSymbolicLink(selectedPath.toPath(), File("/guest-only/no-host/python3").toPath())
        assertFalse(selectedPath.exists())
        assertEquals("./.venv/bin/python", selected(".venv/bin/python").interpreter)
        assertEquals("/guest-only/no-host/python3", selected("/guest-only/no-host/python3").interpreter)
        assertTrue(Files.isSymbolicLink(selectedPath.toPath()))
        assertEquals("/guest-only/no-host/python3", Files.readSymbolicLink(selectedPath.toPath()).toString())
    }

    @Test fun nonExecutableInterpreterFileIsLeftUnchanged() {
        val interpreter = File(temp.root, ".venv/bin/python").apply {
            parentFile.mkdirs()
            writeText("preserve this file")
            assertTrue(setExecutable(false, false))
        }
        val originalPermissions = Files.getPosixFilePermissions(interpreter.toPath())
        assertEquals("./.venv/bin/python", selected(".venv/bin/python").interpreter)
        assertEquals("preserve this file", interpreter.readText())
        assertEquals(originalPermissions, Files.getPosixFilePermissions(interpreter.toPath()))
    }

    @Test fun malformedOrMissingInterpreterNeverFallsBack() {
        val invalid = listOf("", " ", "[]", "null", "{", "{}", "{\"interpreter\":null}",
            "{\"interpreter\":3}", "{\"interpreter\":true}", "{\"interpreter\":[]}", "{\"interpreter\":{}}",
            "{interpreter:python3}", "{\"interpreter\":\"python3\"} /* unfinished",
            "{\"interpreter\":\"python3\",\"activate\":true}")
        for (text in invalid) {
            configFile(text)
            rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
        }
    }

    @Test fun optionsShellCommandsEmptyValuesAndRelativeEscapesAreRejected() {
        for (value in listOf("", " ", "-python", "--help", "-env/bin/python", "python3 -I", "python3;id",
            "python3&&id", "python3|id", "${'$'}PYTHON", "~", ".", "..", "/", "venv/", "venv/.",
            "venv/..", "../venv/bin/python", "venv/../../python", "./../python")) {
            configFile("{\"interpreter\":${JsonPrimitive(value)}}")
            rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
        }
    }

    @Test fun escapedAndRawControlCharactersAreRejected() {
        for (character in listOf('\u0000', '\n', '\r', '\t', '\u001b', '\u007f', '\u0085', '\u202e', '\u2028', '\u2029')) {
            configFile("{\"interpreter\":${JsonPrimitive("env${character}/bin/python")}}")
            rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
        }
        configFile("{\"interpreter\":\"env\n/bin/python\"}")
        rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
    }

    @Test fun interpreterLengthIsBounded() {
        assertEquals("p".repeat(ProjectPythonConfiguration.MAX_INTERPRETER_LENGTH), selected("p".repeat(ProjectPythonConfiguration.MAX_INTERPRETER_LENGTH)).interpreter)
        configFile("{\"interpreter\":${JsonPrimitive("p".repeat(ProjectPythonConfiguration.MAX_INTERPRETER_LENGTH + 1))}}")
        rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
    }

    @Test fun configurationLimitUsesBytesAndRejectsOversizeInput() {
        val base = "{\"interpreter\":\"python3\"}"
        val file = configFile(base + " ".repeat(WorkspaceJsonConfig.MAX_BYTES - base.length))
        assertEquals(WorkspaceJsonConfig.MAX_BYTES.toLong(), file.length())
        assertEquals("python3", ProjectPythonConfiguration.load(temp.root)!!.interpreter)
        file.appendText(" ")
        rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
        file.writeText(base + "/*" + "é".repeat(WorkspaceJsonConfig.MAX_BYTES / 2) + "*/")
        assertTrue(file.readText().length < WorkspaceJsonConfig.MAX_BYTES)
        rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
    }

    @Test fun malformedUtf8CannotBeSilentlyReplaced() {
        val file = configFile("unused")
        file.writeBytes("{\"interpreter\":\"env/".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "/python\"}".toByteArray())
        rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
    }

    @Test fun escapedLoneSurrogatesAreRejectedButValidUnicodePathsArePreserved() {
        for (escaped in listOf("\\ud800", "\\udfff")) {
            configFile("{\"interpreter\":\"env/$escaped/python\"}")
            rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
        }
        assertEquals("./環境/🐍/bin/python", selected("環境/🐍/bin/python").interpreter)
    }

    @Test fun realHostVenvRunsLiteralSourceAndModuleWithoutActivationOrPip() {
        val environment = "env \$(touch sentinel) with space"
        val venv = File(temp.root, environment)
        val setup = try {
            ProcessBuilder("python3", "-m", "venv", "--without-pip", venv.absolutePath)
                .directory(temp.root).redirectErrorStream(true).start()
        } catch (missing: IOException) {
            assumeNoException("This optional host fixture requires an already installed Python", missing)
            return
        }
        val setupOutput = completedOutput(setup)
        assertEquals(setupOutput, 0, setup.exitValue())
        val source = "source with spaces;\$(touch sentinel).py"
        File(temp.root, source).writeText("import sys\nprint(sys.prefix)\nprint(sys.prefix != sys.base_prefix)\n")
        // A local module fixture exercises the exact -m argv without installing pytest.
        File(temp.root, "pytest.py").writeText("import sys\nprint(sys.prefix)\nprint('MODULE_FIXTURE')\n")
        val beforeEnvironment = System.getenv().toMap()
        val selection = selected("$environment/bin/python")
        val run = ProcessBuilder(selection.runArgv(source)).directory(temp.root).redirectErrorStream(true).start()
        val runOutput = completedOutput(run)
        assertEquals(runOutput, 0, run.exitValue())
        assertEquals(listOf(venv.absolutePath, "True"), runOutput.trimEnd().lines())
        val module = ProcessBuilder(selection.pytestArgv()).directory(temp.root).redirectErrorStream(true).start()
        val moduleOutput = completedOutput(module)
        assertEquals(moduleOutput, 0, module.exitValue())
        assertEquals(listOf(venv.absolutePath, "MODULE_FIXTURE"), moduleOutput.trimEnd().lines())
        assertEquals(beforeEnvironment, System.getenv())
        assertFalse(File(venv, "bin/pip").exists())
        assertFalse(File(temp.root, "sentinel").exists())
    }

    @Test fun directoriesAndNonDirectoryMetadataCannotActAsConfiguration() {
        val root = temp.newFolder("workspace")
        File(root, ".droide").writeText("not a directory")
        rejectConfiguration { ProjectPythonConfiguration.load(root) }
        assertEquals("not a directory", File(root, ".droide").readText())
        File(root, ".droide").delete()
        File(root, ProjectPythonConfiguration.CONFIG_PATH).mkdirs()
        rejectConfiguration { ProjectPythonConfiguration.load(root) }
    }

    @Test fun unreadableSelectedFileNeverFallsBack() {
        val file = configFile("{\"interpreter\":\"python3\"}")
        val permissions = Files.getPosixFilePermissions(file.toPath())
        try {
            Files.setPosixFilePermissions(file.toPath(), emptySet<PosixFilePermission>())
            assumeFalse("Privileged test process can still read this file", Files.isReadable(file.toPath()))
            rejectConfiguration { ProjectPythonConfiguration.load(temp.root) }
        } finally {
            Files.setPosixFilePermissions(file.toPath(), permissions)
        }
    }

    @Test fun externalConfigAndMetadataDirectoryLinksAreRejected() {
        val outside = temp.newFolder("outside")
        val external = File(outside, "python.json").apply { writeText("{\"interpreter\":\"python3\"}") }
        val root = temp.newFolder("workspace")
        File(root, ".droide").mkdir()
        val config = File(root, ProjectPythonConfiguration.CONFIG_PATH)
        Files.createSymbolicLink(config.toPath(), external.toPath())
        rejectConfiguration { ProjectPythonConfiguration.load(root) }
        Files.delete(config.toPath())
        File(root, ".droide").delete()
        Files.createSymbolicLink(File(root, ".droide").toPath(), outside.toPath())
        rejectConfiguration { ProjectPythonConfiguration.load(root) }
        assertEquals("{\"interpreter\":\"python3\"}", external.readText())
        assertEquals(1, outside.listFiles()!!.size)
    }

    @Test fun brokenConfigAndMetadataDirectoryLinksNeverCountAsAbsent() {
        val root = temp.newFolder("workspace")
        File(root, ".droide").mkdir()
        val config = File(root, ProjectPythonConfiguration.CONFIG_PATH)
        Files.createSymbolicLink(config.toPath(), File(root, "missing.json").toPath())
        rejectConfiguration { ProjectPythonConfiguration.load(root) }
        Files.delete(config.toPath())
        File(root, ".droide").delete()
        Files.createSymbolicLink(File(root, ".droide").toPath(), File(root, "missing-directory").toPath())
        rejectConfiguration { ProjectPythonConfiguration.load(root) }
    }

    @Test fun inProjectConfigurationLinksUseTheTargetBytes() {
        val root = temp.newFolder("workspace")
        val target = File(root, "selected-python.json").apply { writeText("{\"interpreter\":\"python3.12\"}") }
        File(root, ".droide").mkdir()
        Files.createSymbolicLink(File(root, ProjectPythonConfiguration.CONFIG_PATH).toPath(), target.toPath())
        val selection = ProjectPythonConfiguration.load(root)!!
        assertEquals("python3.12", selection.interpreter)
        assertEquals(sha256(target.readBytes()), selection.configSha256)
    }

    @Test fun loadingAndPlanningDoNotModifyTheProjectOrEnvironment() {
        val config = configFile("{\"interpreter\":\".venv/bin/python\"}")
        val before = config.readBytes()
        val modifiedAt = config.lastModified()
        val selection = ProjectPythonConfiguration.load(temp.root)!!
        repeat(3) { selection.runArgv("main.py"); selection.pytestArgv() }
        assertArrayEquals(before, config.readBytes())
        assertEquals(modifiedAt, config.lastModified())
        assertFalse(File(temp.root, ".venv").exists())
        assertEquals(setOf(".droide", ".droide/python.json"), temp.root.walkTopDown().drop(1).map { it.relativeTo(temp.root).invariantSeparatorsPath }.toSet())
    }

    private fun configFile(text: String): File = File(temp.root, ProjectPythonConfiguration.CONFIG_PATH).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun selected(interpreter: String): ProjectPythonConfiguration {
        configFile("{\"interpreter\":${JsonPrimitive(interpreter)}}")
        return ProjectPythonConfiguration.load(temp.root)!!
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun completedOutput(process: Process): String {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("Host Python fixture timed out")
        }
        return process.inputStream.bufferedReader().readText()
    }

    private fun rejectConfiguration(action: () -> Any?) {
        try {
            action()
            fail("Invalid explicit Python selection must not fall back")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains(ProjectPythonConfiguration.CONFIG_PATH))
            assertTrue(failure.message.orEmpty().contains("Fix this file or remove it"))
        }
    }
}
