package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCopyMembershipConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun simultaneousFirstCopiesShareAuthorityWithoutBlockingOtherProjects() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(8) { round ->
                val parent = temp.newFolder("round-$round")
                val home = File(parent, "host-recovery")
                val repositories = (0 until 8).map { index ->
                    FileRepository(File(parent, "project-$index").apply { check(mkdir()) }).apply {
                        configureCopyRecoveryHome(home)
                    }
                }
                val ready = CountDownLatch(repositories.size)
                val start = CountDownLatch(1)
                val futures = repositories.mapIndexed { index, repository ->
                    executor.submit<String> {
                        val journal = repository.copyRecoveryJournal()
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
                            "run-$index", ProjectCopyJournal.explorerSourceId("content://fixture/document"))
                        journal.begin(owner, "asset.bin", planStage = true).use { it.intent.id }
                    }
                }
                try { assertTrue(ready.await(10, TimeUnit.SECONDS)) }
                finally { start.countDown() }
                val ids = futures.map { it.get(10, TimeUnit.SECONDS) }
                repositories.forEachIndexed { index, repository ->
                    val recovery = repository.copyRecoveryJournal().recovery(
                        ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT))
                    assertNull(recovery.blockingProblem)
                    assertEquals(ids[index], recovery.records.single().intent.id)
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
