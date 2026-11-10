package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectPythonRunIntegrationTest {
    private fun workspace(action: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-python-run").toFile()
        try { File(root, "hello.py").writeText("print('hello')\n"); action(root) }
        finally { root.deleteRecursively() }
    }
    private fun select(root: File, text: String = "{\"interpreter\":\".venv/bin/python\"}") {
        File(root, ".droide").mkdirs(); File(root, ".droide/python.json").writeText(text)
    }
    private fun plan(root: File): RunPlan = (DroideCli.handle(listOf("run", "hello.py"), root) as DroideCli.Result.Run).plan

    @Test fun absentSelectionPreservesDefaultArgvButBindsAbsence() = workspace { root ->
        val result = plan(root)
        assertEquals(listOf(listOf("python3", "hello.py")), result.steps)
        assertEquals("absent", result.pythonConfigurationIdentity)
        assertTrue(PythonRunApproval.capture(result)!!.matches(plan(root)))
    }
    @Test fun explicitSelectionAppliesWithoutCreatingEnvironment() = workspace { root ->
        select(root)
        assertEquals(listOf(listOf("./.venv/bin/python", "hello.py")), plan(root).steps)
        assertFalse(File(root, ".venv").exists())
    }
    @Test fun spacesAndShellCharactersStayOneLiteralArgument() = workspace { root ->
        select(root, "{\"interpreter\":\"env space/$(touch injected)/python\"}")
        assertEquals("./env space/$(touch injected)/python", plan(root).steps.single().first())
        assertFalse(File(root, "injected").exists())
    }
    @Test fun invalidConfigurationCannotUseContributedFallback() = workspace { root ->
        select(root, "{broken}")
        val error = DroideCli.handle(listOf("run", "hello.py"), root) as DroideCli.Result.Err
        assertFalse(error.contributedFallbackAllowed)
        assertTrue(error.msg.contains(".droide/python.json"))
    }
    @Test fun otherLanguagesIgnorePythonConfiguration() = workspace { root ->
        select(root, "{broken}"); File(root, "hello.js").writeText("console.log('hello')")
        val result = DroideCli.handle(listOf("run", "hello.js"), root) as DroideCli.Result.Run
        assertEquals(listOf(listOf("node", "hello.js")), result.plan.steps)
        assertNull(PythonRunApproval.capture(result.plan))
    }
    @Test fun configAppearingAfterApprovalRequiresNewApproval() = workspace { root ->
        val approved = PythonRunApproval.capture(plan(root))!!
        select(root)
        assertFalse(approved.matches(plan(root)))
    }
    @Test fun configRemovalAfterApprovalRequiresNewApproval() = workspace { root ->
        select(root); val approved = PythonRunApproval.capture(plan(root))!!
        File(root, ".droide/python.json").delete()
        assertFalse(approved.matches(plan(root)))
    }
    @Test fun exactConfigBytesParticipateEvenWhenInterpreterIsUnchanged() = workspace { root ->
        select(root); val approved = PythonRunApproval.capture(plan(root))!!
        File(root, ".droide/python.json").appendText("\n")
        assertFalse(approved.matches(plan(root)))
    }
    @Test fun approvalChecksFullArgvAndPreparation() = workspace { root ->
        val current = plan(root); val approved = PythonRunApproval.capture(current)!!
        assertFalse(approved.matches(current.copy(steps = listOf(listOf("other-python", "hello.py")))))
        assertFalse(approved.matches(current.copy(steps = listOf(listOf("python3", "other.py")))))
        assertFalse(approved.matches(current.copy(preparation = RunPreparation(".droide/run/other", emptyMap()))))
        assertFalse(approved.matches(current.copy(pythonConfigurationIdentity = null)))
    }
    @Test fun approvalSnapshotsMutableCallerArgv() {
        val argv = mutableListOf("python3", "hello.py")
        val original = RunPlan(listOf(argv), "Python", pythonConfigurationIdentity = "absent")
        val approved = PythonRunApproval.capture(original)!!
        argv[0] = "changed"
        assertEquals("python3", approved.plan.steps.single().first())
        assertFalse(approved.matches(original))
    }
    @Test fun approvalResourceAndPreviewIdentifyLiteralInterpreter() = workspace { root ->
        select(root); val approved = PythonRunApproval.capture(plan(root))!!
        assertTrue(approved.preview.contains("./.venv/bin/python"))
        val resource = approved.permissionResource("request")
        assertTrue(resource.contains("sha256:")); assertTrue(resource.contains("workspace-root"))
        assertNotEquals(resource, approved.permissionResource("other request"))
    }
    @Test fun selectedOptionLikeFilenameIsNotAnInterpreterFlag() = workspace { root ->
        select(root); File(root, "-test.py").writeText("print('ok')")
        val result = DroideCli.handle(listOf("run", "-test.py"), root) as DroideCli.Result.Run
        assertEquals(listOf("./.venv/bin/python", "./-test.py"), result.plan.steps.single())
    }
}
