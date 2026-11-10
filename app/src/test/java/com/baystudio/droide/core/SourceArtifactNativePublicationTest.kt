package com.baystudio.droide.core

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before

/** Actual baseline JNI loaded by java.library.path. Linux host evidence, not Android device proof. */
class SourceArtifactNativePublicationTest {
    @Before fun requireExplicitNativeHarness() {
        assumeTrue("Requires the freshly compiled JNI variant harness",
            System.getProperty("artifact.native.enabled") == "true")
    }
    private val owner = OwnedResponseContentStore.Owner("native-session", "run", "call")
    private val argsHash = "b".repeat(64)
    private fun runCase(block: (SourceArtifactPublisher, SourceArtifactPublisher.Observation, java.nio.file.Path, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("artifact-native")
        try {
            val project = Files.createDirectory(root.resolve("project")); val private = Files.createDirectory(root.resolve("private"))
            val admission = object : OwnedResponseContentStore.DiskAdmission { override fun reserve(bytes: Long): AutoCloseable = AutoCloseable {} }
            val session = OwnedResponseContentStore(Files.createDirectory(root.resolve("blobs")), admission).authorize(owner)
            val content = session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT).use { writer ->
                repeat(96) { writer.append("native-😀\r\n".repeat(1024)) }
                writer.sealCompleted { input -> val b = ByteArray(8192); while (input.read(b) >= 0) {} }.content.handle
            }
            val native = SourceArtifactPublisher.Publication { from, name, to, target ->
                AnchoredWorkspaceFiles(from.toFile()).use { source ->
                    AnchoredWorkspaceFiles(to.toFile()).use { destination -> source.moveNoReplaceDurable(name, destination, target) }
                }
            }
            val backend = SourceArtifactPublisher(project.toFile(), private, admission, native)
            val observation = backend.prepare(UUID.randomUUID().toString(), owner, argsHash, "native", session, content)
            block(backend, observation, project, private)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun largePrivateStageNativeRenameAndReopen() {
        assumeTrue(System.getProperty("artifact.native.syncError") != "true")
        runCase { backend, o, project, private ->
            val receipt = backend.publish(o, {})
            assertTrue(receipt.durabilityConfirmed); assertTrue(o.bytes > 500_000)
            assertFalse(Files.exists(private.resolve(o.operationId).resolve("payload")))
            assertArrayEquals(("# native\n\n" + "native-😀\r\n".repeat(96 * 1024)).toByteArray(), Files.readAllBytes(project.resolve(o.path)))
            val result = backend.inspect(o.operationId, owner, argsHash) as SourceArtifactPublisher.Recovery.Published
            assertTrue(result.receipt.durabilityConfirmed)
        }
    }
    @Test fun nativeCollisionReceiptPreservesBothFiles() {
        assumeTrue(System.getProperty("artifact.native.syncError") != "true")
        val root = Files.createTempDirectory("artifact-collision")
        try {
            val a = Files.createDirectory(root.resolve("private")); val b = Files.createDirectory(root.resolve("project"))
            Files.write(a.resolve("stage"), byteArrayOf(1,2)); Files.write(b.resolve("target"), byteArrayOf(3))
            AnchoredWorkspaceFiles(a.toFile()).use { source -> AnchoredWorkspaceFiles(b.toFile()).use { target ->
                val result = source.moveNoReplaceDurable("stage", target, "target")
                assertFalse(result.published); assertEquals(17, result.error)
            } }
            assertArrayEquals(byteArrayOf(1,2), Files.readAllBytes(a.resolve("stage")))
            assertArrayEquals(byteArrayOf(3), Files.readAllBytes(b.resolve("target")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun actualNativeSyncErrorStillReportsPublishedReceipt() {
        assumeTrue(System.getProperty("artifact.native.syncError") == "true")
        runCase { backend, o, project, private ->
            val failure = assertThrows(SourceArtifactPublisher.PublicationFailure::class.java) { backend.publish(o, {}) }
            assertNotNull(failure.receipt); assertFalse(failure.receipt!!.durabilityConfirmed)
            assertTrue(Files.exists(project.resolve(o.path))); assertFalse(Files.exists(private.resolve(o.operationId).resolve("payload")))
            val recovery = backend.inspect(o.operationId, owner, argsHash) as SourceArtifactPublisher.Recovery.Published
            assertFalse(recovery.receipt.durabilityConfirmed)
        }
    }
}
