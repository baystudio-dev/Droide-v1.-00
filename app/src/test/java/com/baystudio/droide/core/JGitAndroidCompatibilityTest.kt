package com.baystudio.droide.core

import android.app.Application
import android.os.Process
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class JGitAndroidCompatibilityTest {
    @Test fun forcedGcUsesAndroidPidPreservesObjectsAndReleasesLock() {
        val root = Files.createTempDirectory("jgit-android-gc-").toFile()
        val oldPid = Process.myPid()
        try {
            ShadowProcess.setPid(4242)
            Git.init().setDirectory(root).call().use { git ->
                repeat(3) { revision ->
                    root.resolve("kept.txt").writeText("content-$revision\n")
                    git.add().addFilepattern("kept.txt").call()
                    git.commit().setMessage("fixture $revision")
                        .setAuthor("Fixture", "fixture@example.invalid")
                        .setCommitter("Fixture", "fixture@example.invalid").call()
                }
                val repo = git.repository as FileRepository
                val head = requireNotNull(repo.resolve(Constants.HEAD))
                var observedPid: String? = null
                val gc = GC(repo)
                gc.setProgressMonitor(object : ProgressMonitor {
                    override fun start(totalTasks: Int) {
                        observedPid = repo.directory.resolve("gc.pid").readText().substringBefore(' ')
                    }
                    override fun beginTask(title: String, totalWork: Int) = Unit
                    override fun update(completed: Int) = Unit
                    override fun endTask() = Unit
                    override fun isCancelled(): Boolean = false
                    override fun showDuration(enabled: Boolean) = Unit
                })
                val packs = gc.gc().get(20, TimeUnit.SECONDS)
                assertEquals("4242", observedPid)
                assertFalse(packs.isEmpty())
                assertFalse(repo.directory.resolve("gc.pid").exists())
                assertEquals(head, repo.resolve(Constants.HEAD))
                requireNotNull(TreeWalk.forPath(repo, "kept.txt", requireNotNull(repo.resolve("HEAD^{tree}")))).use { walk ->
                    assertEquals("content-2\n", String(repo.open(walk.getObjectId(0)).bytes, Charsets.UTF_8))
                }
                assertEquals("content-2\n", root.resolve("kept.txt").readText())
            }
        } finally {
            ShadowProcess.setPid(oldPid)
            root.deleteRecursively()
        }
    }

    @Test fun translationBundleReflectionAndResourceRemainAvailable() {
        assertFalse(JGitText.get().authenticationNotSupported.isNullOrBlank())
        assertFalse(JGitText.get().gcFailed.isNullOrBlank())
    }
}
