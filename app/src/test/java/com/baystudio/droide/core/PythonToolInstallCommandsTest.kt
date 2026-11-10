package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class PythonToolInstallCommandsTest {
    @Test fun isolatedInstallerIgnoresProjectVenvPipAndPythonpathModules() = host { f ->
        f.shadowModules()
        val result = f.run(f.recipe().command)
        assertEquals(result.output, 0, result.exit)
        assertFalse(f.shadowMarker.exists())
        assertTrue(f.launcher.isFile)
        assertEquals(listOf("-I", "-m", "venv", f.env.absolutePath), f.creationArgs())
        assertEquals(listOf("--version", "install|--upgrade|--|fixture-tool==2", "check"), f.pipCalls())
        assertEquals(0, f.run(f.recipe().healthCheckCommand).exit)
    }

    @Test fun nonIsolatedProjectModuleNegativeControlActuallyExecutesHostileModule() = host { f ->
        f.shadowModules()
        assertEquals(83, f.run("\"\$REAL_PYTHON\" -m venv ignored").exit)
        assertTrue(f.shadowMarker.exists())
        f.shadowMarker.delete()
        assertEquals(83, f.run("\"\$REAL_PYTHON\" -m pip --version").exit)
        assertTrue(f.shadowMarker.exists())
    }

    @Test fun hostileHomePackageAndHealthArgumentsRemainLiteral() = host { f ->
        val marker = File(f.root, "injected")
        val spec = "fixture-tool'; touch ${marker.absolutePath}; '"
        val arg = "--version'; touch ${marker.absolutePath}; '"
        val recipe = PythonToolInstallCommands.recipe(FAMILY, spec, COMMAND, listOf(arg))
        val result = f.run(recipe.command)
        assertEquals(result.output, 0, result.exit)
        assertTrue(f.pipCalls().contains("install|--upgrade|--|$spec"))
        assertEquals(arg + "\n", File(f.root, "health-args").readText())
        assertFalse(marker.exists())
    }

    @Test fun healthyVirtualenvIsReusedOnRepeatedUpgrades() = host { f ->
        assertEquals(0, f.run(f.recipe().command).exit)
        val config = File(f.env, "pyvenv.cfg").readBytes()
        val sentinel = File(f.env, "retained-user-state").apply { writeText("retain") }
        val second = f.run(f.recipe().command)
        assertEquals(second.output, 0, second.exit)
        assertEquals(1, f.creationCount())
        assertArrayEquals(config, File(f.env, "pyvenv.cfg").readBytes())
        assertEquals("retain", sentinel.readText())
    }

    @Test fun brokenExistingDirectoryIsDiagnosedWithoutRecreatingOrReplacingIt() = host { f ->
        f.env.mkdirs()
        val sentinel = File(f.env, "keep").apply { writeText("user state") }
        val result = f.run(f.recipe().command)
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains("inspect it in Terminal"))
        assertEquals("user state", sentinel.readText())
        assertEquals(0, f.creationCount())
        assertFalse(File(f.env, "bin/python").exists())
    }

    @Test fun directoryThatOnlyLooksLikeAVirtualenvCannotReachPipInstall() = host { f ->
        File(f.env, "bin").mkdirs()
        File(f.env, "pyvenv.cfg").writeText("not a virtualenv config")
        // A wrapper around the base interpreter must not be accepted as a virtualenv.
        File(f.env, "bin/python").apply {
            writeText("#!/bin/sh\nexec \"\$REAL_PYTHON\" \"\$@\"\n")
            setExecutable(true)
        }
        val result = f.run(f.recipe().command)
        assertNotEquals(0, result.exit)
        assertFalse(File(f.root, "pip-calls").exists())
        assertEquals(0, f.creationCount())
    }

    @Test fun existingVirtualenvWithBrokenPipIsNotRecreated() = host { f ->
        assertEquals(0, f.run(f.recipe().command).exit)
        File(f.root, "pip-calls").delete()
        val result = f.run(f.recipe().command, mapOf("FIXTURE_PIP_VERSION_EXIT" to "37"))
        assertEquals(37, result.exit)
        assertTrue(result.output.contains("no working pip"))
        assertEquals(1, f.creationCount())
        assertEquals(listOf("--version"), f.pipCalls())
    }

    @Test fun oldPathExecutableCannotMaskMissingOrBrokenNewExecutable() = host { f ->
        f.oldPathTool()
        // Negative control: the previous PATH-only check succeeds even with no installation.
        assertEquals(0, f.run("command -v fixture-tool >/dev/null && fixture-tool --version").exit)
        val missing = f.run(f.recipe().command, mapOf("FIXTURE_MISSING_CLI" to "1"))
        assertNotEquals(0, missing.exit)
        assertFalse(Files.exists(f.launcher.toPath()))
        assertNotEquals(0, f.run(f.recipe().healthCheckCommand).exit)
        val broken = f.run(f.recipe().command, mapOf("FIXTURE_HEALTH_EXIT" to "41"))
        assertEquals(41, broken.exit)
        assertFalse(Files.exists(f.launcher.toPath()))
    }

    @Test fun pipInstallAndDependencyCheckFailuresKeepExitStatusAndDoNotPublish() = host { f ->
        val install = f.run(f.recipe().command, mapOf("FIXTURE_INSTALL_EXIT" to "29"))
        assertEquals(29, install.exit)
        assertFalse(Files.exists(f.launcher.toPath()))
        val check = f.run(f.recipe().command, mapOf("FIXTURE_CHECK_EXIT" to "31"))
        assertEquals(31, check.exit)
        assertFalse(Files.exists(f.launcher.toPath()))
    }

    @Test fun oldSameEnvVersionCannotMaskAnExplicitUpgradeThatDidNotInstallThere() = host { f ->
        assertEquals(0, f.run(f.recipe().command).exit)
        val request = PythonToolInstallCommands.recipe(FAMILY, "fixture-tool==3", COMMAND, listOf("--version"))
        val result = f.run(request.command, mapOf("FIXTURE_KEEP_OLD_VERSION" to "1"))
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains("requested 3, found 2"))
        assertNotEquals(0, f.run(request.healthCheckCommand).exit)
        assertEquals(1, f.creationCount())
        assertTrue(Files.isSymbolicLink(f.launcher.toPath()))
        assertEquals(0, f.run(f.recipe().healthCheckCommand).exit)
    }

    @Test fun explicitVersionComparisonUsesRealPep440Normalization() = host { f ->
        val request = PythonToolInstallCommands.recipe(FAMILY, "fixture-tool==v2.0.0", COMMAND, listOf("--version"))
        val result = f.run(request.command, mapOf("FIXTURE_INSTALLED_VERSION" to "2.0"))
        assertEquals(result.output, 0, result.exit)
        assertEquals(0, f.run(request.healthCheckCommand).exit)
    }

    @Test fun latestChecksWorkingExactEnvWithoutClaimingFreshness() = host { f ->
        assertEquals(0, f.run(f.recipe().command).exit)
        val latest = PythonToolInstallCommands.recipe(FAMILY, "fixture-tool", COMMAND, listOf("--version"))
        assertEquals(0, f.run(latest.command, mapOf("FIXTURE_KEEP_OLD_VERSION" to "1")).exit)
        assertEquals(0, f.run(latest.healthCheckCommand).exit)
        assertFalse(latest.healthCheckCommand.contains("from importlib.metadata"))
    }

    @Test fun wrapperPublicationReplacesSymlinkWithoutTruncatingItsTarget() = host { f ->
        assertEquals(0, f.run(f.recipe(module = "debugpy").command).exit)
        val target = File(f.env, "bin/$COMMAND").apply { writeText("preserve installed script\n") }
        f.launcher.delete()
        Files.createSymbolicLink(f.launcher.toPath(), target.toPath())
        val result = f.run(f.recipe(module = "debugpy").command, mapOf("FIXTURE_MISSING_CLI" to "1"))
        assertEquals(result.output, 0, result.exit)
        assertFalse(Files.isSymbolicLink(f.launcher.toPath()))
        assertEquals("preserve installed script\n", target.readText())
        assertEquals(f.wrapper(), f.launcher.readText())
        assertEquals(0, f.run("\"\$HOME/.local/bin/$COMMAND\" --version").exit)
        assertEquals("0", File(f.root, "module-isolated").readText())
        assertFalse(f.launcher.readText().contains("-I"))
        assertTrue(f.launcher.parentFile.listFiles()!!.none { it.name.startsWith(".droide-python-") })
    }

    @Test fun legacyRedirectionNegativeControlProvesSymlinkTargetWouldBeTruncated() = host { f ->
        val target = File(f.root, "legacy custom target").apply { writeText("original") }
        f.launcher.parentFile.mkdirs()
        Files.createSymbolicLink(f.launcher.toPath(), target.toPath())
        assertEquals(0, f.run("printf '%s\\n' '#!/bin/sh' 'legacy replacement' > \"\$HOME/.local/bin/$COMMAND\"").exit)
        assertEquals("#!/bin/sh\nlegacy replacement\n", target.readText())
        assertTrue(Files.isSymbolicLink(f.launcher.toPath()))
    }

    @Test fun symlinkToDirectoryIsReplacedWithoutTouchingDirectoryContents() = host { f ->
        assertEquals(0, f.run(f.recipe(module = "debugpy").command).exit)
        val target = File(f.env, "bin/$COMMAND").apply { delete(); mkdir() }
        val keep = File(target, "keep").apply { writeText("unchanged") }
        f.launcher.delete()
        Files.createSymbolicLink(f.launcher.toPath(), target.toPath())
        val result = f.run(f.recipe(module = "debugpy").command, mapOf("FIXTURE_MISSING_CLI" to "1"))
        assertEquals(result.output, 0, result.exit)
        assertFalse(Files.isSymbolicLink(f.launcher.toPath()))
        assertEquals(f.wrapper(), f.launcher.readText())
        assertEquals("unchanged", keep.readText())
        assertEquals(listOf("keep"), target.listFiles()!!.map { it.name })
    }

    @Test fun unrelatedCustomSymlinkIsPreservedAndRejectedBeforeInstallation() = host { f ->
        val target = File(f.root, "custom launcher").apply { writeText("preserve custom contents") }
        f.launcher.parentFile.mkdirs()
        Files.createSymbolicLink(f.launcher.toPath(), target.toPath())
        val result = f.run(f.recipe(module = "debugpy").command)
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains("not owned by Droide"))
        assertTrue(Files.isSymbolicLink(f.launcher.toPath()))
        assertEquals(target.toPath(), Files.readSymbolicLink(f.launcher.toPath()))
        assertEquals("preserve custom contents", target.readText())
        assertEquals(0, f.creationCount())
    }

    @Test fun existingExactDroideSymlinkCanBeUpdated() = host { f ->
        f.launcher.parentFile.mkdirs()
        Files.createSymbolicLink(f.launcher.toPath(), File(f.env, "bin/$COMMAND").toPath())
        val result = f.run(f.recipe().command)
        assertEquals(result.output, 0, result.exit)
        assertTrue(Files.isSymbolicLink(f.launcher.toPath()))
        assertEquals(File(f.env, "bin/$COMMAND").toPath(), Files.readSymbolicLink(f.launcher.toPath()))
        assertEquals(0, f.run(f.recipe().command).exit)
        assertEquals(1, f.creationCount())
    }

    @Test fun symlinkOwnershipComparisonDoesNotDiscardTrailingTargetNewlines() = host { f ->
        f.launcher.parentFile.mkdirs()
        val target = File(f.env, "bin/$COMMAND\n").toPath()
        Files.createSymbolicLink(f.launcher.toPath(), target)
        assertNotEquals(0, f.run(f.recipe().command).exit)
        assertEquals(target, Files.readSymbolicLink(f.launcher.toPath()))
        assertEquals(0, f.creationCount())
    }

    @Test fun customRegularFileAndDirectoryCollisionsStopBeforeInstallation() = host { f ->
        f.launcher.parentFile.mkdirs()
        f.launcher.writeText("custom launcher\n")
        val regular = f.run(f.recipe(module = "debugpy").command)
        assertNotEquals(0, regular.exit)
        assertTrue(regular.output.contains("move it aside in Terminal"))
        assertEquals("custom launcher\n", f.launcher.readText())
        assertEquals(0, f.creationCount())
        f.launcher.delete()
        f.launcher.mkdir()
        val keep = File(f.launcher, "keep").apply { writeText("keep") }
        assertNotEquals(0, f.run(f.recipe().command).exit)
        assertEquals("keep", keep.readText())
        assertEquals(0, f.creationCount())
    }

    @Test fun exactLegacyWrapperIsRecognizedButModifiedWrapperIsPreserved() = host { f ->
        f.launcher.parentFile.mkdirs()
        f.launcher.writeText(f.wrapper())
        assertEquals(0, f.run(f.recipe(module = "debugpy").command).exit)
        assertEquals(f.wrapper(), f.launcher.readText())
        f.launcher.appendText("# user customization\n")
        val edited = f.launcher.readText()
        assertNotEquals(0, f.run(f.recipe(module = "debugpy").command).exit)
        assertEquals(edited, f.launcher.readText())
        assertEquals(1, f.creationCount())
    }

    @Test fun moduleHealthRunsIsolatedAndFailsBeforePublishingWhenModuleIsMissingOrBroken() = host { f ->
        f.shadowModules()
        val missing = f.run(f.recipe(module = "debugpy").command, mapOf("FIXTURE_MISSING_MODULE" to "1"))
        assertNotEquals(0, missing.exit)
        assertFalse(Files.exists(f.launcher.toPath()))
        val broken = f.run(f.recipe(module = "debugpy").command, mapOf("FIXTURE_MODULE_EXIT" to "43"))
        assertEquals(43, broken.exit)
        assertFalse(Files.exists(f.launcher.toPath()))
        assertEquals("1", File(f.root, "module-isolated").readText())
        assertFalse(f.shadowMarker.exists())
    }

    @Test fun failedAtomicRenameKeepsPreviousLauncherAndCleansOnlyOwnedTemporaryFile() = host { f ->
        assertEquals(0, f.run(f.recipe(module = "debugpy").command).exit)
        val target = File(f.env, "bin/$COMMAND").apply { writeText("keep") }
        f.launcher.delete()
        Files.createSymbolicLink(f.launcher.toPath(), target.toPath())
        val unrelated = File(f.launcher.parentFile, ".droide-python-unrelated").apply { writeText("keep") }
        f.fake("mv", "exit 47")
        assertEquals(47, f.run(f.recipe(module = "debugpy").command, mapOf("FIXTURE_MISSING_CLI" to "1")).exit)
        assertTrue(Files.isSymbolicLink(f.launcher.toPath()))
        assertEquals("keep", target.readText())
        assertEquals("keep", unrelated.readText())
        assertEquals(setOf(COMMAND, unrelated.name), f.launcher.parentFile.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun aliasedVirtualenvIsRejectedWithoutChangingItsTarget() = host { f ->
        val target = File(f.root, "external environment").apply { mkdirs() }
        val keep = File(target, "keep").apply { writeText("keep") }
        f.env.parentFile.mkdirs()
        Files.createSymbolicLink(f.env.toPath(), target.toPath())
        assertNotEquals(0, f.run(f.recipe().command).exit)
        assertEquals("keep", keep.readText())
        assertTrue(Files.isSymbolicLink(f.env.toPath()))
        assertEquals(0, f.creationCount())
    }

    @Test fun symlinkedInstallParentsAreRejectedBeforeAnyMutation() {
        for (path in listOf(".local", ".local/share", ".local/share/droide-tools")) host { f ->
            val target = File(f.root, "outside install tree").apply { mkdirs() }
            val sentinel = File(target, "keep").apply { writeText("untouched") }
            val parent = File(f.home, path)
            parent.parentFile.mkdirs()
            Files.createSymbolicLink(parent.toPath(), target.toPath())
            val result = f.run(f.recipe().command)
            assertNotEquals(path, 0, result.exit)
            assertTrue(result.output.contains("parent is aliased"))
            assertTrue(Files.isSymbolicLink(parent.toPath()))
            assertEquals("untouched", sentinel.readText())
            assertEquals(listOf("keep"), target.listFiles()!!.map { it.name })
            assertEquals(0, f.creationCount())
        }
    }

    @Test fun symlinkedLauncherParentIsRejectedBeforeAnyInstallation() = host { f ->
        val target = File(f.root, "outside launchers").apply { mkdirs() }
        val sentinel = File(target, COMMAND).apply { writeText("custom launcher") }
        val parent = File(f.home, ".local/bin")
        parent.parentFile.mkdirs()
        Files.createSymbolicLink(parent.toPath(), target.toPath())
        val result = f.run(f.recipe().command)
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains("parent is aliased"))
        assertEquals("custom launcher", sentinel.readText())
        assertEquals(0, f.creationCount())
        assertFalse(f.env.exists())
    }

    @Test fun nonDirectoryParentEntriesArePreservedAndRejected() {
        for (path in listOf(".local", ".local/share", ".local/share/droide-tools", ".local/bin")) host { f ->
            val parent = File(f.home, path)
            parent.parentFile.mkdirs()
            parent.writeText("user file")
            assertNotEquals(path, 0, f.run(f.recipe().command).exit)
            assertEquals("user file", parent.readText())
            assertEquals(0, f.creationCount())
        }
    }

    @Test fun unsafePathSelectorsAndModulesAreRejected() {
        for (family in listOf("../escape", "/absolute", ".", "a/b", "a\nline")) {
            assertThrows(IllegalArgumentException::class.java) { PythonToolInstallCommands.recipe(family, "fixture", COMMAND, emptyList()) }
        }
        assertThrows(IllegalArgumentException::class.java) { PythonToolInstallCommands.recipe(FAMILY, "fixture", "../escape", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PythonToolInstallCommands.recipe(FAMILY, "fixture", COMMAND, emptyList(), "debugpy;evil") }
    }

    private data class Result(val exit: Int, val output: String)
    private fun host(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("droide-python-test-").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        val home = File(root, "home with 'quote; \$literal").apply { mkdirs() }
        val project = File(root, "project").apply { mkdirs() }
        val env = File(home, ".local/share/droide-tools/$FAMILY")
        val launcher = File(home, ".local/bin/$COMMAND")
        val shadowMarker = File(root, "shadow-executed")
        private val bin = File(root, "bin").apply { mkdirs() }

        init {
            File(root, "fixture-pip.py").writeText("""
                import os, pathlib, sys, sysconfig
                root = pathlib.Path(os.environ['FIXTURE_ROOT'])
                args = sys.argv[1:]
                with (root / 'pip-calls').open('a') as log: log.write('|'.join(args) + '\n')
                if args == ['--version']:
                    sys.exit(int(os.environ.get('FIXTURE_PIP_VERSION_EXIT', '0')))
                if args == ['check']:
                    sys.exit(int(os.environ.get('FIXTURE_CHECK_EXIT', '0')))
                if args[:3] != ['install', '--upgrade', '--']: sys.exit(91)
                status = int(os.environ.get('FIXTURE_INSTALL_EXIT', '0'))
                if status: sys.exit(status)
                if os.environ.get('FIXTURE_KEEP_OLD_VERSION') != '1':
                    metadata = pathlib.Path(sysconfig.get_path('purelib')) / 'fixture_tool-2.dist-info'
                    metadata.mkdir(exist_ok=True)
                    installed = os.environ.get('FIXTURE_INSTALLED_VERSION', args[3].partition('==')[2] or '2')
                    (metadata / 'METADATA').write_text('Metadata-Version: 2.1\nName: fixture-tool\nVersion: ' + installed + '\n')
                if os.environ.get('FIXTURE_MISSING_CLI') != '1':
                    cli = pathlib.Path(sys.prefix) / 'bin' / '$COMMAND'
                    cli.write_text('#!/bin/sh\nprintf "%s\\n" "${'$'}@" > "${'$'}FIXTURE_ROOT/health-args"\nexit "${'$'}{FIXTURE_HEALTH_EXIT:-0}"\n')
                    cli.chmod(0o755)
                if os.environ.get('FIXTURE_MISSING_MODULE') != '1':
                    module = pathlib.Path(sysconfig.get_path('purelib')) / 'debugpy'
                    module.mkdir(exist_ok=True)
                    (module / '__init__.py').write_text('')
                    (module / '__main__.py').write_text("import os, pathlib, sys\npathlib.Path(os.environ['FIXTURE_ROOT'], 'module-isolated').write_text(str(sys.flags.isolated))\nsys.exit(int(os.environ.get('FIXTURE_MODULE_EXIT', '0')))\n")
            """.trimIndent())
            fake("python3", """
                printf '%s\0' "${'$'}@" >> "${'$'}FIXTURE_ROOT/creation-args"
                printf 'create\n' >> "${'$'}FIXTURE_ROOT/creation-count"
                "${'$'}REAL_PYTHON" "${'$'}@" --without-pip || exit ${'$'}?
                for tool_env; do :; done
                site=${'$'}("${'$'}tool_env/bin/python" -I -c 'import sysconfig; print(sysconfig.get_path("purelib"))') || exit ${'$'}?
                mkdir -p "${'$'}site/pip" || exit ${'$'}?
                : > "${'$'}site/pip/__init__.py"
                cp "${'$'}FIXTURE_ROOT/fixture-pip.py" "${'$'}site/pip/__main__.py"
                mkdir -p "${'$'}site/pip/_vendor" || exit ${'$'}?
                : > "${'$'}site/pip/_vendor/__init__.py"
                cp -R "${'$'}FIXTURE_PACKAGING" "${'$'}site/pip/_vendor/packaging"
            """.trimIndent())
        }

        fun recipe(module: String? = null) = PythonToolInstallCommands.recipe(FAMILY, "fixture-tool==2", COMMAND, listOf("--version"), module)
        fun wrapper() = "#!/bin/sh\nexec \"\$HOME/.local/share/droide-tools/$FAMILY/bin/python\" -m debugpy \"\$@\"\n"
        fun creationCount() = File(root, "creation-count").takeIf { it.exists() }?.readLines()?.size ?: 0
        fun creationArgs() = File(root, "creation-args").readText().split('\u0000').dropLast(1)
        fun pipCalls() = File(root, "pip-calls").readLines()
        fun oldPathTool() = fake(COMMAND, "exit 0")
        fun fake(name: String, body: String) {
            File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
        }
        fun shadowModules() {
            for (name in listOf("venv", "pip", "debugpy")) File(project, "$name.py").writeText(
                "import os, pathlib\npathlib.Path(os.environ['FIXTURE_ROOT'], 'shadow-executed').write_text('$name')\nraise SystemExit(83)\n")
        }
        fun run(command: String, extra: Map<String, String> = emptyMap()): Result {
            val output = File(root, "output")
            val process = ProcessBuilder("/bin/sh", "-c", command).directory(project).redirectErrorStream(true).redirectOutput(output).apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = "${bin.absolutePath}:/usr/bin:/bin"
                environment()["REAL_PYTHON"] = "/usr/bin/python3"
                environment()["FIXTURE_ROOT"] = root.absolutePath
                environment()["FIXTURE_PACKAGING"] = packagingDirectory
                environment()["PYTHONPATH"] = project.absolutePath
                environment().putAll(extra)
            }.start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Python shell fixture timed out") }
            return Result(process.exitValue(), output.readText())
        }
    }

    companion object {
        private const val FAMILY = "quality.fixture-tool"
        private const val COMMAND = "fixture-tool"
        private val packagingDirectory: String by lazy {
            val configured = System.getenv("DROIDE_TEST_PIP_PACKAGING")
            if (configured != null) {
                require(File(configured, "version.py").isFile) { "DROIDE_TEST_PIP_PACKAGING must name an existing pip-vendored packaging directory" }
                return@lazy configured
            }
            val process = ProcessBuilder("python3", "-I", "-c",
                "import pathlib, pip._vendor.packaging; print(pathlib.Path(pip._vendor.packaging.__file__).parent)")
                .redirectErrorStream(true).start()
            assertTrue("Python packaging fixture discovery timed out", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals("Python with an existing pip installation is needed for offline normalization fixtures: $output", 0, process.exitValue())
            output
        }
    }
}
