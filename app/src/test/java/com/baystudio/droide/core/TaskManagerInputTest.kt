package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskManagerInputTest {
    @Test fun malformedTaskIsRejectedWholeWhileEmptyArgumentsArePreserved() = runBlocking {
        val root = Files.createTempDirectory("droide-task-input-").toFile()
        try {
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/tasks.json").writeText("""{"tasks":[
              {"label":"valid","command":["python3",""],"executionScope":"local-linux-arm64"},
              {"label":"bad-object","command":["python3",{}]},
              {"label":"bad-number","command":["python3",12]},
              {"label":"bad-cwd","command":["python3"],"cwd":[]},
              {"label":"bad-scope","command":["python3"],"executionScope":false},
              {"label":"local-app","command":["python3"],"executionScope":"local"}
            ]}""")
            val tasks = TaskManager(root).list()
            assertEquals(listOf("valid"), tasks.map { it.label })
            assertEquals(listOf("python3", ""), tasks.single().argv)
            assertEquals(TaskExecutionScope.LOCAL_LINUX_ARM64, tasks.single().executionScope)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun projectMetadataSymlinkOutsideWorkspaceIsNotAutodetected() = runBlocking {
        val root = Files.createTempDirectory("droide-task-root-").toFile()
        val other = Files.createTempDirectory("droide-task-other-").toFile()
        try {
            val metadata = other.resolve("package.json").apply { writeText("""{"scripts":{"test":"private"}}""") }
            Files.createSymbolicLink(root.resolve("package.json").toPath(), metadata.toPath())
            assertTrue(TaskManager(root).list().isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(other) }
    }
    @Test fun vscodeRelativeFileDirectoryIsDotAtWorkspaceRootAndUnchangedBelowIt() = runBlocking {
        val root = Files.createTempDirectory("droide-task-directory-").toFile()
        try {
            root.resolve("main.py").writeText("")
            root.resolve("src/main.py").apply { parentFile.mkdirs(); writeText("") }
            root.resolve(".vscode/tasks.json").apply {
                parentFile.mkdirs()
                writeText("""{"tasks":[{"label":"directory","type":"process","command":"find","args":["${'$'}{relativeFileDirname}","${'$'}{fileExtname}"]}]}""")
            }
            val atRoot = TaskManager(root, activeFile = { "main.py" }).list().single()
            assertEquals(listOf("find", ".", ".py"), atRoot.argv)
            val nested = TaskManager(root, activeFile = { "src/main.py" }).list().single()
            assertEquals(listOf("find", "src", ".py"), nested.argv)
            for ((name, extension) in listOf(".gitignore" to "", "archive." to ".", ".env.local" to ".local")) {
                root.resolve(name).writeText("")
                assertEquals(listOf("find", ".", extension), TaskManager(root, activeFile = { name }).list().single().argv)
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun basenameWithoutExtensionPreservesDotfiles() = runBlocking {
        val root = Files.createTempDirectory("droide-task-basename-").toFile()
        try {
            root.resolve(".vscode/tasks.json").apply {
                parentFile.mkdirs()
                writeText("""{"tasks":[{"label":"basename","type":"process","command":"echo","args":["${'$'}{fileBasenameNoExtension}"]}]}""")
            }
            for ((name, expected) in listOf(".env" to ".env", ".gitignore" to ".gitignore", ".env.local" to ".env", "archive." to "archive", "main.test.py" to "main.test", "README" to "README")) {
                root.resolve(name).writeText("")
                assertEquals(name, listOf("echo", expected), TaskManager(root, activeFile = { name }).list().single().argv)
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun taskLabelsRemainExactAndDistinctWhenLongOrSpacePadded() = runBlocking {
        val root = Files.createTempDirectory("droide-task-identity-").toFile()
        try {
            val first = "x".repeat(240) + "-first"
            val second = "x".repeat(240) + "-second"
            val padded = " build "
            root.resolve(".vscode/tasks.json").apply {
                parentFile.mkdirs()
                writeText("""{"tasks":[
                    {"label":"$first","type":"process","command":"echo","args":["first"]},
                    {"label":"$second","type":"process","command":"echo","args":["second"]},
                    {"label":"$padded","type":"process","command":"echo","args":["padded"]},
                    {"label":"all","dependsOn":["$first","$second","$padded"]}
                ]}""")
            }
            val executed = mutableListOf<String>()
            val manager = TaskManager(root, taskExecutor = { task, _ ->
                executed += task.label
                TaskResult(task, 0, "", false, 0)
            })
            assertEquals(listOf(first, second, padded, "all"), manager.list().map { it.label })
            manager.runNamed("all")
            assertEquals(listOf(first, second, padded, "all"), executed)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun vscodePathSeparatorAliasesResolveInArgumentsCwdAndEnvironment() = runBlocking {
        val root = Files.createTempDirectory("droide-task-separators-").toFile()
        try {
            root.resolve("src").mkdir()
            root.resolve(".vscode/tasks.json").apply {
                parentFile.mkdirs()
                writeText("""{"tasks":[{"label":"separator","type":"process","command":"echo",
                  "args":["a${'$'}{pathSeparator}b","a${'$'}{/}b"],
                  "options":{"cwd":"${'$'}{workspaceFolder}${'$'}{/}src","env":{"PARTS":"a${'$'}{pathSeparator}b"}}}]}""")
            }
            val task = TaskManager(root).list().single()
            assertEquals(listOf("echo", "a/b", "a/b"), task.argv)
            assertEquals(root.resolve("src").canonicalFile, task.cwd)
            assertEquals("a/b", task.environment["PARTS"])
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
