package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Real receipt/authority/integrity/projection APIs; only process execution is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35], application = Application::class,
    shadows = [R37AllowlistedLocalShell::class, R37NoLocalProcess::class],
    instrumentedPackages = [
        "com.baystudio.droide.core.LocalExecutionSubstrate",
        "com.baystudio.droide.core.LocalProcessSupervisor",
    ],
)
class LocalManagedUbuntuJqDispatchTest {
    companion object {
        // As in LocalManagedProjectionParentGuardTest: Robolectric replaces its Application
        // per method, while the process-owned substrate must keep one exact app files root.
        private val applicationRoot = Files.createTempDirectory("r37-jq-application-").toFile().canonicalFile
    }

    // TestRule wraps @Before, the test method and @After, including runBlocking setup.
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var registry: ManagedPackageRegistry
    private lateinit var authority: LocalManagedPackageAuthority
    private val ownedFamilies = listOf("cli.jq", "cli.git", "runtime.ubuntu-deps.r37-jq", "runtime.ubuntu-deps.r37-git", "cli.r37-raw")

    @Before fun prepare() = runBlocking {
        // Check both shadows before any call that could reach a process API. An unmatched
        // supervisor method is a no-op/null, never the real ProcessBuilder implementation.
        assertTrue(Shadow.extract<Any>(LocalExecutionSubstrate) is R37AllowlistedLocalShell)
        assertTrue(Shadow.extract<Any>(LocalProcessSupervisor) is R37NoLocalProcess)
        val actual = LocalExecutionSubstrate::class.java.declaredMethods.single { it.name == "shellBounded" }
        val fake = R37AllowlistedLocalShell::class.java.getDeclaredMethod("shellBounded", *actual.parameterTypes)
        assertEquals(actual.returnType, fake.returnType)
        assertNotNull(fake.getAnnotation(Implementation::class.java))
        val canary = R37ShellCall("r37-shadow-canary", 17, 23)
        R37AllowlistedLocalShell.expect(listOf(canary))
        assertEquals(0, LocalExecutionSubstrate.shellBounded(canary.command, canary.maxOutputBytes, canary.timeoutMs).exitCode)
        R37AllowlistedLocalShell.assertConsumed(listOf(canary))
        R37AllowlistedLocalShell.expect(emptyList())

        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = applicationRoot
        }
        root = context.filesDir.canonicalFile
        ownedFamilies.forEach { assertFalse("Previous fixture payload survived cleanup", File(root, "packages/$it").exists()) }
        assertFalse(File(root, "managed").exists())
        assertFalse(File(root, "packages/.staging").exists())
        assertFalse(File(root, "packages/.previous").exists())
        assertFalse(File(root, "packages/.trash/local-authority").exists())
        assertTrue(context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).edit().clear().commit())
        LocalExecutionSubstrate.init(context)
        val foreign = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "foreign-root")
        }
        val rejection = runCatching { LocalExecutionSubstrate.init(foreign) }.exceptionOrNull()
        assertTrue(rejection is IllegalStateException)
        assertEquals("Cannot rebind the local execution substrate to another application root", rejection?.message)
        LocalExecutionSubstrate.requireContextRoot(root.path)
        registry = ManagedPackageRegistry(context)
        authority = LocalManagedPackageAuthority(context, registry)
    }

    @After fun cleanup() {
        check(!LocalPackageInstallJournal.transactionMutex.isLocked && !ManagedPackageMutationGate.mutex.isLocked) {
            "A fixture package operation is still active"
        }
        R37AllowlistedLocalShell.expect(emptyList())
        if (::context.isInitialized) context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).edit().clear().commit()
        if (::root.isInitialized) {
            ownedFamilies.forEach { PathSecurity.deleteTreeNoFollow(File(root, "packages/$it")) }
            PathSecurity.deleteTreeNoFollow(File(root, "managed"))
            PathSecurity.deleteTreeNoFollow(File(root, "packages/.staging"))
            PathSecurity.deleteTreeNoFollow(File(root, "packages/.previous"))
            PathSecurity.deleteTreeNoFollow(File(root, "packages/.trash/local-authority"))
            PathSecurity.deleteTreeNoFollow(File(root, "packages/.trash/cli.jq-Alpine_3.24"))
            PathSecurity.deleteTreeNoFollow(File(root, "r37-root-alias"))
            PathSecurity.deleteTreeNoFollow(File(root, "r37-outside-sentinel"))
        }
    }

    @Test fun ubuntuJqAdoptsVerifiesAndPublishesBothCommandProjections() = runBlocking {
        val (record, layer) = ubuntu("cli.jq")
        val adoptCalls = listOf(health(layer), health(layer), health(record))
        R37AllowlistedLocalShell.expect(adoptCalls)
        authority.adopt(record)
        R37AllowlistedLocalShell.assertConsumed(adoptCalls)
        assertEquals(record, registry.find(record.familyId, record.version))
        assertTrue(File(root, "managed/bin/jq").readText().contains("${record.installRoot}/payload/host-bin/jq"))
        assertTrue(File(root, "managed/ubuntu-bin/jq").readText().contains("/opt/droide/packages/cli.jq/ubuntu-24.04/arm64-v8a/payload/bin/jq"))
        val verifyCalls = listOf(health(layer), health(record))
        R37AllowlistedLocalShell.expect(verifyCalls)
        assertTrue(authority.verify(record))
        R37AllowlistedLocalShell.assertConsumed(verifyCalls)
    }

    @Test fun anotherUbuntuAptFamilyRetainsItsExistingRoute() = runBlocking {
        val (record, layer) = ubuntu("cli.git")
        val calls = listOf(health(layer), health(record))
        R37AllowlistedLocalShell.expect(calls)
        assertTrue(authority.verify(record))
        R37AllowlistedLocalShell.assertConsumed(calls)
    }

    // Explicitly supersedes standalone dispatch-v3 legacy success only in this retirement overlay.
    @Test fun authenticUntypedLegacyJqIsRetiredWithoutAnyShellAttempt() = runBlocking {
        val record = legacy()
        R37AllowlistedLocalShell.expect(emptyList())
        assertFalse(authority.verify(record))
        R37AllowlistedLocalShell.assertConsumed(emptyList())
        R37AllowlistedLocalShell.assertNoAttempts()
    }

    @Test fun explicitUnknownOrMalformedKindCannotFallBackToLegacyJq() = runBlocking {
        val record = legacy()
        for (kind in listOf("", "alpine-apk", "unknown-v1"))
            rejected(record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.KIND_KEY to kind)))
    }

    @Test fun explicitEnvironmentMetadataCannotMasqueradeAsUntypedLegacyJq() = runBlocking {
        val record = legacy()
        for (profile in listOf("", DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64.name,
            DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name))
            rejected(record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.PROFILE_KEY to profile)))
        rejected(record.copy(metadata = record.metadata + (FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to "foreign-layer")))
    }

    @Test fun jqCannotUseGenericLayerRouteEvenWithUnknownOrEmptyLocalKind() = runBlocking {
        val (_, layer) = ubuntu("cli.jq")
        val forged = layer.copy(familyId = "cli.jq", version = UbuntuAptCatalog.VERSION)
        rejected(forged)
        for (kind in listOf("", "unknown-v1", LocalManagedPackageMetadata.KIND_UBUNTU_APT))
            rejected(forged.copy(metadata = forged.metadata + (LocalManagedPackageMetadata.KIND_KEY to kind)))
    }

    @Test fun ubuntuJqRejectsEveryConflictingRuntimeKindBeforeHealth() = runBlocking {
        val (record, _) = ubuntu("cli.jq")
        for (kind in listOf("", "foreign-layer", FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND))
            rejected(record.copy(metadata = record.metadata + (FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to kind)))
    }

    @Test fun ubuntuReceiptNeedsItsKindAndCannotClaimTheLegacyVersion() = runBlocking {
        val (record, _) = ubuntu("cli.jq")
        rejected(record.copy(metadata = record.metadata - LocalManagedPackageMetadata.KIND_KEY))
        rejected(record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.KIND_KEY to "")))
        rejected(record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.KIND_KEY to "unknown-v1")))
        rejected(record.copy(version = WorkstationGuestEnvironmentSpec.VERSION))
    }

    @Test fun ubuntuReceiptRequiresItsProducerEnvironmentScopeAndAbi() = runBlocking {
        val (record, _) = ubuntu("cli.jq")
        rejected(record.copy(metadata = record.metadata - LocalManagedPackageMetadata.PROFILE_KEY))
        rejected(record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.PROFILE_KEY to "")))
        rejected(record.copy(metadata = record.metadata +
            (LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64.name)))
        rejected(record.copy(scope = ExecutionScope.entries.first { it != ExecutionScope.LOCAL_LINUX_ARM64 }.name))
        rejected(record.copy(abi = null))
        rejected(record.copy(abi = "x86_64"))
    }

    @Test fun ubuntuReceiptRejectsWrongCommandPathAndForeignBackendBeforeHealth() = runBlocking {
        val (record, _) = ubuntu("cli.jq")
        rejected(record.copy(commands = mapOf("jq" to "${record.installRoot}/payload/bin/jq")))
        rejected(record.copy(installRoot = "${root.path}/packages/../foreign"))
        rejected(record.copy(metadata = record.metadata + (PackageBackendContract.METADATA_KEY to PackageBackendId.DEVICE_ADB.name)))
    }

    @Test fun ubuntuReceiptRequiresOneOwnedMatchingLayer() = runBlocking {
        val (record, layer) = ubuntu("cli.jq")
        rejected(record.copy(dependencies = emptyList()))
        rejected(record.copy(dependencies = record.dependencies + record.dependencies))
        rejected(record.copy(dependencies = listOf("runtime.ubuntu-deps.missing@${layer.version}")))
        registry.replaceAll(listOf(layer.copy(metadata = layer.metadata + (FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to "git"))))
        rejected(record)
        registry.replaceAll(listOf(layer.copy(metadata = layer.metadata + (PackageBackendContract.METADATA_KEY to PackageBackendId.DEVICE_ADB.name))))
        rejected(record)
    }

    @Test fun ubuntuReceiptRejectsAlteredSealPayloadAndActivation() = runBlocking {
        val (record, _) = ubuntu("cli.jq")
        rejected(record.copy(artifactSha256 = "0".repeat(64)))
        rejected(record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.ACTIVATION_ID_KEY to "wrong-activation")))
        File(record.installRoot, "payload/host-bin/jq").appendText("# altered\n")
        rejected(record)
    }

    @Test fun ubuntuJqStillRequiresSuccessfulCommandHealth() = runBlocking {
        val (record, layer) = ubuntu("cli.jq")
        val calls = listOf(health(layer), health(record).copy(exitCode = 9))
        R37AllowlistedLocalShell.expect(calls)
        assertFalse(authority.verify(record))
        R37AllowlistedLocalShell.assertConsumed(calls)
        assertNull(registry.find(record.familyId, record.version))
    }

    @Test fun legacyJqStillRejectsWrongVersionDependenciesPathAndAlteredPayload() = runBlocking {
        val record = legacy()
        rejected(record.copy(version = UbuntuAptCatalog.VERSION))
        rejected(record.copy(dependencies = listOf("runtime.other@1")))
        rejected(record.copy(commands = mapOf("jq" to "${record.installRoot}/payload/host-bin/jq")))
        File(record.installRoot, "payload/bin/jq").appendText("# altered\n")
        rejected(record)
    }

    @Test fun rejectedJqKeepsItsDurableReceiptCurrentTreeRemovalBackupAndIntentUnchanged() = runBlocking {
        val (record, _) = ubuntu("cli.jq")
        val source = File(record.installRoot)
        val trash = removalBackup(record)
        val intent = LocalPackageRemovalJournal.metadata(trash)
        val sourceBefore = treeBytes(source)
        val trashBefore = treeBytes(trash)
        val intentBefore = intent.readBytes()
        val prefs = context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
        for (metadata in listOf(
            record.metadata + (LocalManagedPackageMetadata.KIND_KEY to "unknown-v1"),
            record.metadata + (FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND),
        )) {
            val rejected = record.copy(metadata = metadata)
            registry.put(rejected)
            val receiptBefore = requireNotNull(prefs.getString("records_json", null)).toByteArray(Charsets.UTF_8)
            R37AllowlistedLocalShell.expect(emptyList())
            val failure = runCatching { authority.reconcile() }.exceptionOrNull()
            assertTrue("Rejected jq must remain an explicit recovery error", failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("not certified for app-local ownership"))
            R37AllowlistedLocalShell.assertConsumed(emptyList())
            assertArrayEquals(receiptBefore, requireNotNull(prefs.getString("records_json", null)).toByteArray(Charsets.UTF_8))
            assertEquals(rejected, registry.find(record.familyId, record.version))
            assertEquals(sourceBefore, treeBytes(source))
            assertEquals(trashBefore, treeBytes(trash))
            assertArrayEquals(intentBefore, intent.readBytes())
        }
    }

    @Test fun healthyNonJqCurrentReceiptStillClearsItsMatchingRemovalBackup() = runBlocking {
        val (record, layer) = ubuntu("cli.git")
        registry.put(record)
        val source = File(record.installRoot)
        val sourceBefore = treeBytes(source)
        val trash = removalBackup(record)
        val calls = listOf(health(layer), health(record))
        R37AllowlistedLocalShell.expect(calls)
        authority.reconcile()
        R37AllowlistedLocalShell.assertConsumed(calls)
        assertEquals(record, registry.find(record.familyId, record.version))
        assertEquals(sourceBefore, treeBytes(source))
        assertFalse(trash.exists())
        assertFalse(LocalPackageRemovalJournal.metadata(trash).exists())
    }

    @Test fun caughtUnexpectedShellCallIsRecordedBeforeAllowlistFailure() = runBlocking {
        R37AllowlistedLocalShell.expect(emptyList())
        val call = R37ShellCall("r37-forbidden-attempt", 19, 29)
        assertTrue(runCatching {
            LocalExecutionSubstrate.shellBounded(call.command, call.maxOutputBytes, call.timeoutMs)
        }.exceptionOrNull() is AssertionError)
        R37AllowlistedLocalShell.assertAttempts(listOf(call))
        R37AllowlistedLocalShell.expect(emptyList())
    }

    @Test fun legacyAutomaticRecoveryPreservesAllReceiptAndRemovalEvidence() = runBlocking {
        for (mode in listOf("current", "old-local", "oldest", "source-missing", "source-unhealthy", "intent-only", "no-payload", "no-backend")) {
            clearRetirementFixture()
            val record = legacy().let { if (mode == "no-backend") it.copy(metadata = emptyMap()) else it }
            registry.put(record)
            val source = File(record.installRoot)
            val trash = when (mode) {
                "old-local" -> File(root, "packages/.trash/local-authority/cli.jq-Alpine_3.24")
                "oldest" -> File(root, "packages/.trash/cli.jq-Alpine_3.24")
                else -> removalBackup(record)
            }
            if (mode == "old-local" || mode == "oldest") {
                assertTrue(trash.parentFile!!.mkdirs() || trash.parentFile!!.isDirectory)
                assertTrue(source.copyRecursively(trash))
            }
            if (mode == "source-missing") assertTrue(PathSecurity.deleteTreeNoFollow(source))
            if (mode == "source-unhealthy") File(source, "payload/bin/jq").writeText("broken legacy wrapper")
            if (mode == "intent-only") assertTrue(PathSecurity.deleteTreeNoFollow(trash))
            if (mode == "no-payload") {
                assertTrue(PathSecurity.deleteTreeNoFollow(source))
                assertTrue(PathSecurity.deleteTreeNoFollow(trash))
                assertTrue(LocalPackageRemovalJournal.metadata(trash).delete())
            }
            val before = retirementEvidence()
            repeat(2) {
                R37AllowlistedLocalShell.expect(emptyList())
                val result: Any? = authority.reconcile()
                R37AllowlistedLocalShell.assertNoAttempts()
                assertEquals("Pending legacy mode $mode", true, result)
                assertEquals(before, retirementEvidence())
                assertEquals(record, registry.find(record.familyId, record.version))
            }
        }
    }

    @Test fun exactLegacyOrphansRemainPendingWithoutOwnerOrIntent() = runBlocking {
        for (mode in listOf("intent-and-backup", "intent-only", "hash-only", "old-local", "oldest")) {
            clearRetirementFixture()
            val record = legacy()
            val source = File(record.installRoot)
            val trash = when (mode) {
                "old-local" -> File(root, "packages/.trash/local-authority/cli.jq-Alpine_3.24")
                "oldest" -> File(root, "packages/.trash/cli.jq-Alpine_3.24")
                else -> removalBackup(record)
            }
            if (mode == "old-local" || mode == "oldest") {
                assertTrue(trash.parentFile!!.mkdirs() || trash.parentFile!!.isDirectory)
                assertTrue(source.copyRecursively(trash))
            }
            if (mode == "hash-only") assertTrue(LocalPackageRemovalJournal.metadata(trash).delete())
            if (mode == "intent-only") assertTrue(PathSecurity.deleteTreeNoFollow(trash))
            assertTrue(PathSecurity.deleteTreeNoFollow(source))
            val before = retirementEvidence()
            R37AllowlistedLocalShell.expect(emptyList())
            val result: Any? = authority.reconcile()
            R37AllowlistedLocalShell.assertNoAttempts()
            assertEquals("Pending orphan mode $mode", true, result)
            assertEquals(before, retirementEvidence())
        }
    }

    @Test fun untypedShapeAloneCannotCertifyHistoricalCustody() = runBlocking {
        val record = legacy()
        removalBackup(record)
        val variants = listOf(
            record.copy(version = "Alpine 3.25"),
            record.copy(installRoot = "${root.path}/packages/cli.jq/unproven/arm64-v8a"),
            record.copy(commands = mapOf("jq" to "${record.installRoot}/payload/host-bin/jq")),
            record.copy(pathEntries = emptyList()),
            record.copy(healthChecks = emptyList()),
            record.copy(artifactSha256 = "0".repeat(64)),
            record.copy(scope = ExecutionScope.entries.first { it != ExecutionScope.LOCAL_LINUX_ARM64 }.name),
            record.copy(abi = "x86_64"),
            record.copy(dependencies = listOf("runtime.unknown@1")),
            record.copy(metadata = record.metadata + (PackageBackendContract.METADATA_KEY to PackageBackendId.DEVICE_ADB.name)),
            record.copy(metadata = record.metadata + (LocalManagedPackageMetadata.ACTIVATION_ID_KEY to "00000000-0000-0000-0000-000000000001")),
        )
        for (variant in variants) {
            registry.replaceAll(listOf(variant))
            assertRetainedConflict()
        }
    }

    @Test fun duplicateHistoricalDurableIdentityIsRejectedBeforeFiltering() = runBlocking {
        val record = legacy()
        removalBackup(record)
        val prefs = context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("records_json", Json.encodeToString(listOf(record, record.copy(active = false)))).commit())
        assertEquals(2, registry.list().size)
        val failure = assertRetainedConflict()
        assertTrue(failure.message.orEmpty().contains("duplicate", ignoreCase = true))
    }

    @Test fun legacyCustodyRejectsLinksAndNonDirectoryAncestors() = runBlocking {
        for (mode in listOf("source-link", "family-link", "family-file", "trash-parent-file", "intent-link")) {
            clearRetirementFixture()
            val record = legacy()
            registry.put(record)
            val sentinel = File(root, "r37-outside-sentinel").apply { assertTrue(mkdirs()) }
            File(sentinel, "keep").writeText("outside bytes")
            when (mode) {
                "source-link" -> {
                    assertTrue(PathSecurity.deleteTreeNoFollow(File(record.installRoot)))
                    Files.createSymbolicLink(File(record.installRoot).toPath(), sentinel.toPath())
                }
                "family-link", "family-file" -> {
                    val family = File(root, "packages/cli.jq")
                    assertTrue(PathSecurity.deleteTreeNoFollow(family))
                    if (mode == "family-link") Files.createSymbolicLink(family.toPath(), sentinel.toPath())
                    else family.writeText("not a directory")
                }
                "trash-parent-file" -> {
                    val parent = File(root, "packages/.trash/local-authority")
                    assertTrue(parent.parentFile!!.mkdirs() || parent.parentFile!!.isDirectory)
                    parent.writeText("not a directory")
                }
                "intent-link" -> {
                    val trash = removalBackup(record)
                    val metadata = LocalPackageRemovalJournal.metadata(trash)
                    assertTrue(metadata.delete())
                    Files.createSymbolicLink(metadata.toPath(), File(sentinel, "keep").toPath())
                }
            }
            val outsideBefore = noFollowBytes(sentinel)
            assertRetainedConflict()
            assertEquals(outsideBefore, noFollowBytes(sentinel))
        }
    }

    @Test fun legacyOrphanIdentitySchemaAndGenerationConflictsPreserveBytes() = runBlocking {
        for (mode in listOf("path", "version", "filename", "malformed", "oversized", "generation", "owner-generation")) {
            clearRetirementFixture()
            val record = legacy()
            val trash = removalBackup(record)
            val metadata = LocalPackageRemovalJournal.metadata(trash)
            if (mode == "owner-generation") registry.put(record)
            when (mode) {
                "path" -> metadata.writeText(metadata.readText().replace(record.installRoot, "${root.path}/packages/unproven/1"))
                "version" -> metadata.writeText(metadata.readText().replace(record.version, "Alpine 3.25"))
                "filename" -> assertTrue(metadata.renameTo(File(metadata.parentFile, "cli.jq-wrong.receipt")))
                "malformed" -> metadata.writeText("{}")
                "oversized" -> metadata.writeText(" ".repeat(8193))
                "generation", "owner-generation" -> metadata.writeText(metadata.readText().replace("\"activationId\":null",
                    "\"activationId\":\"00000000-0000-0000-0000-000000000001\""))
            }
            assertRetainedConflict()
        }
    }

    @Test fun wrongFamilyReceiptsAndOrphansCannotClaimHistoricalRoot() = runBlocking {
        for (aliased in listOf(false, true)) for (durable in listOf(false, true)) {
            clearRetirementFixture()
            val old = legacy()
            val alias = File(root, "r37-root-alias")
            val observedAuthority = if (aliased) {
                Files.createSymbolicLink(alias.toPath(), root.toPath())
                LocalManagedPackageAuthority(object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = alias
                }, registry)
            } else authority
            val foreign = old.copy(familyId = "cli.r37-raw", version = "1",
                installRoot = if (aliased) alias.path + old.installRoot.removePrefix(root.path) else old.installRoot,
                metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW))
            if (durable) registry.put(foreign)
            val trash = removalBackup(foreign)
            assertEquals(LocalPackageRemovalJournal.entryName(foreign), trash.name)
            assertNotEquals(LocalPackageRemovalJournal.entryName(old), trash.name)
            val intent = requireNotNull(LocalPackageRemovalJournal.read(trash))
            assertEquals(foreign.familyId, intent.familyId)
            assertTrue(LocalPackageRemovalJournal.belongsTo(intent, foreign))
            val before = retirementEvidence()
            R37AllowlistedLocalShell.expect(emptyList())
            val failure = runCatching { observedAuthority.reconcile() }.exceptionOrNull()
            R37AllowlistedLocalShell.assertNoAttempts()
            // Show the concrete custody mutation on the old code before checking its error.
            assertEquals("Foreign historical-root custody, aliased=$aliased, durable=$durable", before, retirementEvidence())
            assertTrue("Historical-root conflict must be explicit", failure is IllegalStateException)
            if (aliased) assertEquals(root.toPath(), Files.readSymbolicLink(alias.toPath()))
        }
    }

    @Test fun legacyAdoptAndActivateRejectBeforeReceiptOrProjectionEffects() = runBlocking {
        val record = legacy()
        registry.put(record)
        removalBackup(record)
        val before = retirementEvidence()
        val operations = listOf<suspend () -> Unit>({ authority.adopt(record) }, { authority.activate(record) },
            { authority.requirePilot("cli.jq") })
        for (operation in operations) {
            R37AllowlistedLocalShell.expect(emptyList())
            assertTrue(runCatching { operation() }.exceptionOrNull() is UnsupportedOperationException)
            R37AllowlistedLocalShell.assertNoAttempts()
            assertEquals(before, retirementEvidence())
            assertFalse(File(root, "managed").exists())
        }
    }

    @Test fun currentPackageCannotExecuteRetiredDependencyDuringAdoption() = runBlocking {
        val old = legacy()
        registry.put(old)
        val (current, _) = ubuntu("cli.jq")
        val foreignDependency = current.copy(dependencies = listOf("${old.familyId}@${old.version}"))
        val before = retirementEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        assertFalse(authority.verify(foreignDependency))
        assertTrue(runCatching { authority.adopt(foreignDependency) }.exceptionOrNull() is IllegalStateException)
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals(before, retirementEvidence())
        assertFalse(File(root, "managed").exists())
    }

    @Test fun legacyUninstallUsesDurableIdentityAndRejectsBeforeJournalEffects() = runBlocking {
        val record = legacy()
        registry.put(record)
        val before = retirementEvidence()
        val stale = record.copy(active = false, installRoot = "${root.path}/packages/stale-ui")
        R37AllowlistedLocalShell.expect(emptyList())
        assertTrue(runCatching { authority.uninstall(stale) }.exceptionOrNull() is UnsupportedOperationException)
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals(before, retirementEvidence())
        assertFalse(File(root, "managed").exists())
        assertFalse(File(root, "packages/.trash/local-authority").exists())
    }

    @Test fun onlyExactNamedHistoricalGeneratedShimsCanBeRetired() = runBlocking {
        for (mode in listOf("exact", "edited", "no-owner", "unproven-owner", "foreign-target", "symlink", "invalid-utf8")) {
            clearRetirementFixture()
            val record = legacy()
            if (mode != "no-owner") registry.put(if (mode == "unproven-owner") record.copy(version = "Alpine 3.25") else record)
            val expected = hostShim(record.commands.getValue("jq"))
            val sentinel = File(root, "r37-outside-sentinel").apply { assertTrue(mkdirs()) }
            val keep = File(sentinel, "keep").apply { writeText("outside bytes") }
            val directories = listOf("bin", ".staging", ".previous").map { File(root, "managed/$it").apply { assertTrue(mkdirs()) } }
            directories.forEach { directory ->
                val file = File(directory, "jq")
                when (mode) {
                    "edited" -> file.writeText(expected + "# user edit\n")
                    "foreign-target" -> file.writeText(hostShim("${root.path}/packages/unproven/1/payload/bin/jq"))
                    "symlink" -> Files.createSymbolicLink(file.toPath(), keep.toPath())
                    "invalid-utf8" -> file.writeBytes(expected.toByteArray() + byteArrayOf(0xff.toByte()))
                    else -> file.writeText(expected)
                }
                if (mode != "symlink") assertTrue(file.setExecutable(true, true))
                File(directory, "wrong-name").writeText(expected)
            }
            val legacyBefore = retirementEvidence()
            val expectedLeaves = directories.associateWith { noFollowBytes(it) }
            R37AllowlistedLocalShell.expect(emptyList())
            if (mode == "unproven-owner") assertRetainedConflict() else authority.reconcile()
            R37AllowlistedLocalShell.assertNoAttempts()
            assertEquals(legacyBefore, retirementEvidence())
            assertEquals("outside bytes", keep.readText())
            directories.forEach { directory ->
                if (mode == "exact") assertFalse(Files.exists(File(directory, "jq").toPath(), NOFOLLOW_LINKS))
                else assertEquals(expectedLeaves.getValue(directory), noFollowBytes(directory))
                assertEquals(expected, File(directory, "wrong-name").readText())
            }
        }
    }

    @Test fun currentUbuntuJqAdoptsActivatesAndUninstallsWithoutLegacyFallback() = runBlocking {
        val old = legacy()
        registry.put(old)
        val legacyTrash = removalBackup(old)
        val legacyBefore = noFollowBytes(File(old.installRoot))
        val trashBefore = noFollowBytes(legacyTrash)
        val intentBefore = LocalPackageRemovalJournal.metadata(legacyTrash).readBytes()
        val (record, layer) = ubuntu("cli.jq")
        val adoptCalls = listOf(health(layer), health(layer), health(record))
        R37AllowlistedLocalShell.expect(adoptCalls)
        authority.adopt(record)
        R37AllowlistedLocalShell.assertConsumed(adoptCalls)
        assertEquals(old, registry.find(old.familyId, old.version))
        registry.replaceAll(registry.list().map { if (it.familyId == record.familyId && it.version == record.version) it.copy(active = false) else it })
        val activateCalls = listOf(health(layer), health(record))
        R37AllowlistedLocalShell.expect(activateCalls)
        authority.activate(record.copy(active = false))
        R37AllowlistedLocalShell.assertConsumed(activateCalls)
        assertEquals(old, registry.find(old.familyId, old.version))
        assertTrue(File(root, "managed/bin/jq").readText().contains("/ubuntu-24.04/"))
        assertTrue(File(root, "managed/ubuntu-bin/jq").readText().contains("/ubuntu-24.04/"))
        R37AllowlistedLocalShell.expect(emptyList())
        authority.uninstall(record.copy(active = false))
        R37AllowlistedLocalShell.assertNoAttempts()
        assertNull(registry.find(record.familyId, record.version))
        assertEquals(old, registry.find(old.familyId, old.version))
        assertEquals(legacyBefore, noFollowBytes(File(old.installRoot)))
        assertEquals(trashBefore, noFollowBytes(legacyTrash))
        assertArrayEquals(intentBefore, LocalPackageRemovalJournal.metadata(legacyTrash).readBytes())
        assertFalse(File(root, "managed/bin/jq").exists())
        assertFalse(File(root, "managed/ubuntu-bin/jq").exists())
    }

    @Test fun unrelatedCurrentHealthyAndUnhealthyFallbackContractsRemain() = runBlocking {
        for (healthy in listOf(true, false)) {
            clearRetirementFixture()
            val old = rawCurrent("1", active = false)
            val current = rawCurrent("2", active = true)
            registry.replaceAll(listOf(old, current))
            val before = retirementEvidence()
            val calls = listOf(rawExecutable(old), health(old).copy(exitCode = if (healthy) 0 else 9))
            R37AllowlistedLocalShell.expect(calls)
            val result = runCatching { authority.uninstall(current) }
            R37AllowlistedLocalShell.assertConsumed(calls)
            if (healthy) {
                assertTrue(result.isSuccess)
                assertEquals(old.copy(active = true), registry.find(old.familyId, old.version))
                assertFalse(File(current.installRoot).exists())
                assertTrue(File(root, "managed/bin/raw-tool").readText().contains("/cli.r37-raw/1/"))
            } else {
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertEquals(before, retirementEvidence())
                assertFalse(File(root, "managed").exists())
            }
        }
    }

    @Test fun retainedLegacyDefersOnlyAutomaticRegistryNormalizationWrite() = runBlocking {
        val old = legacy()
        val (_, layer) = ubuntu("cli.git")
        val alias = File(root, "r37-root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val aliasContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = alias
        }
        val aliasAuthority = LocalManagedPackageAuthority(aliasContext, registry)
        val aliased = layer.copy(installRoot = alias.path + layer.installRoot.removePrefix(root.path))
        registry.replaceAll(listOf(old, aliased))
        val before = retirementEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        val withLegacy: Any? = aliasAuthority.reconcile()
        assertEquals(true, withLegacy)
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals(before, retirementEvidence())
        registry.replaceAll(listOf(aliased))
        val withoutLegacy: Any? = aliasAuthority.reconcile()
        assertEquals(false, withoutLegacy)
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals(listOf(layer), registry.list())
    }

    @Test fun noLegacyReconciliationReturnsFalseAndKeepsCurrentProjections() = runBlocking {
        val (record, _) = ubuntu("cli.git")
        registry.put(record)
        R37AllowlistedLocalShell.expect(emptyList())
        val current: Any? = authority.reconcile()
        assertEquals(false, current)
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals(record, registry.find(record.familyId, record.version))
        assertTrue(File(root, "managed/bin/git").isFile)
        clearRetirementFixture()
        val empty: Any? = authority.reconcile()
        assertEquals(false, empty)
        R37AllowlistedLocalShell.assertNoAttempts()
    }

    // R38 custody cases model restored/corrupt/unsupported durable claims. Current sealed
    // installers emit canonical roots; no test treats these claims as valid producer output.
    @Test fun rejectedCurrentAliasKeepsOwnedRemovalEvidenceWithSourcePresent() = runBlocking {
        assertRejectedCurrentAliasRemovalPreserved(sourceMissing = false)
    }

    @Test fun rejectedCurrentAliasKeepsOnlyRemainingRemovalBackupWithSourceMissing() = runBlocking {
        assertRejectedCurrentAliasRemovalPreserved(sourceMissing = true)
    }

    @Test fun canonicalCommittedOwnerlessRemovalStillReclaimsItsBackup() = runBlocking {
        val (record, _) = ubuntu("cli.git")
        val trash = removalBackup(record)
        assertTrue(PathSecurity.deleteTreeNoFollow(File(record.installRoot)))
        val registryBefore = registryBytes()
        R37AllowlistedLocalShell.expect(emptyList())
        assertFalse(authority.reconcile())
        R37AllowlistedLocalShell.assertNoAttempts()
        assertFalse(trash.exists())
        assertFalse(LocalPackageRemovalJournal.metadata(trash).exists())
        assertEquals(registryBefore, registryBytes())
        assertNull(registry.find(record.familyId, record.version))
    }

    @Test fun rejectedAliasCannotBecomeReceiptlessRecoveryWithoutPrevious() = runBlocking {
        assertRejectedRecoveryPreserved("alias", withPrevious = false)
    }

    @Test fun rejectedAliasCannotBecomeReceiptlessRecoveryWithPrevious() = runBlocking {
        assertRejectedRecoveryPreserved("alias", withPrevious = true)
    }

    @Test fun foreignBackendCannotBecomeReceiptlessRecoveryWithoutPrevious() = runBlocking {
        assertRejectedRecoveryPreserved("foreign", withPrevious = false)
    }

    @Test fun foreignBackendCannotBecomeReceiptlessRecoveryWithPrevious() = runBlocking {
        assertRejectedRecoveryPreserved("foreign", withPrevious = true)
    }

    @Test fun duplicateDurableIdentityCannotAuthorizeInstallJournalCleanup() = runBlocking {
        assertRejectedRecoveryPreserved("duplicate", withPrevious = false)
    }

    @Test fun anotherDurableIdentityCannotMakeItsClaimedTargetOwnerless() = runBlocking {
        assertRejectedRecoveryPreserved("other-identity", withPrevious = false)
    }

    @Test fun differentDurableKindCannotAuthorizeRequestedInstallJournal() = runBlocking {
        assertRejectedRecoveryPreserved("kind", withPrevious = false)
    }

    @Test fun currentRecoveryKeepsCommittedFinalAndReclaimsInterruptedStageAndPrevious() = runBlocking {
        val (record, _) = ubuntu("cli.git")
        publishCurrentFixture(record)
        val paths = transaction(record)
        seedRecoveryTrees(paths, withPrevious = true, conflictingPrevious = false)
        val finalBefore = noFollowBytes(paths.final)
        val registryBefore = registryBytes()
        val projectionBefore = noFollowBytes(File(root, "managed"))
        R37AllowlistedLocalShell.expect(emptyList())
        assertEquals(record, recoverUsingCurrentInstallerComposition(authority, record))
        R37AllowlistedLocalShell.assertNoAttempts()
        assertFalse(paths.stage.exists())
        assertFalse(paths.previous.exists())
        assertEquals(finalBefore, noFollowBytes(paths.final))
        assertEquals(registryBefore, registryBytes())
        assertEquals(projectionBefore, noFollowBytes(File(root, "managed")))
    }

    @Test fun trulyAbsentReceiptStillDiscardsInterruptedFirstInstallFinalAndStage() = runBlocking {
        val (record, layer) = ubuntu("cli.git")
        val paths = transaction(record)
        seedRecoveryTrees(paths, withPrevious = false)
        val layerBefore = noFollowBytes(File(layer.installRoot))
        val registryBefore = registryBytes()
        R37AllowlistedLocalShell.expect(emptyList())
        assertNull(recoverUsingCurrentInstallerComposition(authority, record))
        R37AllowlistedLocalShell.assertNoAttempts()
        assertFalse(paths.stage.exists())
        assertFalse(paths.final.exists())
        assertFalse(paths.previous.exists())
        assertEquals(layerBefore, noFollowBytes(File(layer.installRoot)))
        assertEquals(registryBefore, registryBytes())
        assertFalse(File(root, "managed").exists())
    }

    @Test fun inactiveJqCannotUninstallAnotherCurrentFamilysTree() = runBlocking {
        assertWrongDestinationUninstallPreserved(wrongVersion = false)
    }

    @Test fun inactiveReceiptCannotUninstallAnotherVersionsTree() = runBlocking {
        assertWrongDestinationUninstallPreserved(wrongVersion = true)
    }

    @Test fun reconciliationRejectsWrongDestinationBeforeTouchingRemovalBackup() = runBlocking {
        val (git, _) = ubuntu("cli.git")
        publishCurrentFixture(git)
        val forged = wrongDestinationReceipt(git, wrongVersion = false)
        registry.put(forged)
        val trash = removalBackup(forged)
        val before = custodyEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        val failure = runCatching { authority.reconcile() }.exceptionOrNull()
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals("Destination rejection must precede restore/discard", before, custodyEvidence())
        assertTrue("Destination conflict must be explicit", failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("transaction destination"))
        assertTrue(trash.isDirectory)
        assertEquals(git, registry.find(git.familyId, git.version))
    }

    @Test fun validInactiveUbuntuReceiptStillUninstallsItsExactTree() = runBlocking {
        val (git, _) = ubuntu("cli.git")
        publishCurrentFixture(git)
        val (jq, _) = ubuntu("cli.jq")
        val inactive = jq.copy(active = false)
        registry.put(inactive)
        val gitBefore = noFollowBytes(File(git.installRoot))
        val projectionBefore = noFollowBytes(File(root, "managed"))
        R37AllowlistedLocalShell.expect(emptyList())
        assertTrue(authority.uninstall(inactive).startsWith("Uninstalled"))
        R37AllowlistedLocalShell.assertNoAttempts()
        assertFalse(File(jq.installRoot).exists())
        assertNull(registry.find(jq.familyId, jq.version))
        assertEquals(git, registry.find(git.familyId, git.version))
        assertEquals(gitBefore, noFollowBytes(File(git.installRoot)))
        assertEquals(projectionBefore, noFollowBytes(File(root, "managed")))
    }

    private suspend fun assertRejectedCurrentAliasRemovalPreserved(sourceMissing: Boolean) {
        val (record, _) = ubuntu("cli.git")
        publishCurrentFixture(record)
        val (aliasAuthority, aliased) = sealedAlias(record)
        registry.put(aliased)
        val trash = removalBackup(aliased)
        if (sourceMissing) assertTrue(PathSecurity.deleteTreeNoFollow(File(record.installRoot)))
        val before = custodyEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        val failure = runCatching { aliasAuthority.reconcile() }.exceptionOrNull()
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals("Rejected alias sourceMissing=$sourceMissing must retain all custody", before, custodyEvidence())
        assertTrue("Rejected alias custody must remain an explicit conflict", failure is IllegalStateException)
        assertEquals(aliased, registry.find(aliased.familyId, aliased.version))
        assertEquals(aliased, aliasAuthority.normalizeReceipt(aliased))
        assertFalse(aliasAuthority.owns(aliased))
        assertNull(aliasAuthority.ownedReceipt(aliased.familyId, aliased.version))
        assertTrue(trash.isDirectory)
        assertEquals(root.toPath(), Files.readSymbolicLink(File(root, "r37-root-alias").toPath()))
    }

    private suspend fun assertRejectedRecoveryPreserved(mode: String, withPrevious: Boolean) {
        val (record, _) = ubuntu("cli.git")
        publishCurrentFixture(record)
        var observedAuthority = authority
        val requestedKind = if (mode == "kind") LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW
            else LocalManagedPackageMetadata.KIND_UBUNTU_APT
        val paths = transaction(record, requestedKind)
        seedRecoveryTrees(paths, withPrevious)
        val rejected = when (mode) {
            "alias" -> sealedAlias(record).let { (aliasAuthority, aliased) ->
                observedAuthority = aliasAuthority
                aliased
            }
            "foreign" -> record.copy(metadata = record.metadata +
                (PackageBackendContract.METADATA_KEY to PackageBackendId.DEVICE_ADB.name))
            "other-identity" -> wrongDestinationReceipt(record, wrongVersion = false)
            else -> record
        }
        if (mode == "other-identity") registry.remove(record.familyId, record.version)
        registry.put(rejected)
        if (mode == "duplicate") {
            val rows = registry.list() + rejected.copy(active = false)
            assertTrue(context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).edit()
                .putString("records_json", Json.encodeToString(rows)).commit())
            assertEquals(2, registry.list().count { it.familyId == record.familyId && it.version == record.version })
        }
        if (mode == "other-identity") assertNull(registry.find(record.familyId, record.version))
        else assertNotNull(registry.find(record.familyId, record.version))
        if (mode == "alias" || mode == "foreign" || mode == "other-identity")
            assertNull(observedAuthority.ownedReceipt(record.familyId, record.version))
        val before = custodyEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        val failure = runCatching {
            recoverUsingCurrentInstallerComposition(observedAuthority, record, requestedKind)
        }.exceptionOrNull()
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals("Recovery mode=$mode previous=$withPrevious must not mutate durable custody", before, custodyEvidence())
        assertTrue("Rejected recovery must be explicit", failure is IllegalStateException)
    }

    private suspend fun assertWrongDestinationUninstallPreserved(wrongVersion: Boolean) {
        val (git, _) = ubuntu("cli.git")
        publishCurrentFixture(git)
        val forged = wrongDestinationReceipt(git, wrongVersion)
        registry.put(forged)
        assertNotEquals(transaction(forged).final.path, forged.installRoot)
        val before = custodyEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        val failure = runCatching { authority.uninstall(forged) }.exceptionOrNull()
        R37AllowlistedLocalShell.assertNoAttempts()
        assertEquals("Wrong destination uninstall version=$wrongVersion must preserve all custody", before, custodyEvidence())
        assertTrue("Destination conflict must be explicit", failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("transaction destination"))
        assertEquals(git, registry.find(git.familyId, git.version))
        assertEquals(forged, registry.find(forged.familyId, forged.version))
    }

    private fun wrongDestinationReceipt(git: ManagedPackageRecord, wrongVersion: Boolean) = git.copy(
        familyId = if (wrongVersion) git.familyId else "cli.jq",
        version = if (wrongVersion) "ubuntu-24.04-r38" else UbuntuAptCatalog.VERSION,
        active = false, dependencies = emptyList(), commands = emptyMap(), pathEntries = emptyList(), healthChecks = emptyList(),
    )

    private suspend fun publishCurrentFixture(record: ManagedPackageRecord) {
        registry.put(record)
        R37AllowlistedLocalShell.expect(emptyList())
        assertFalse(authority.reconcile())
        R37AllowlistedLocalShell.assertNoAttempts()
    }

    private fun sealedAlias(record: ManagedPackageRecord): Pair<LocalManagedPackageAuthority, ManagedPackageRecord> {
        val alias = File(root, "r37-root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val aliasAuthority = LocalManagedPackageAuthority(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = alias
        }, registry)
        val aliased = record.copy(installRoot = alias.path + record.installRoot.removePrefix(root.path))
        assertEquals(aliased, aliasAuthority.normalizeReceipt(aliased))
        assertFalse(aliasAuthority.owns(aliased))
        return aliasAuthority to aliased
    }

    private fun transaction(record: ManagedPackageRecord, kind: String = record.metadata.getValue(LocalManagedPackageMetadata.KIND_KEY)) =
        LocalPackageInstallJournal.paths(root.path, record.familyId, record.version, kind)

    private fun seedRecoveryTrees(paths: LocalPackageInstallJournal.Paths, withPrevious: Boolean, conflictingPrevious: Boolean = true) {
        assertTrue(paths.stage.mkdirs())
        File(paths.stage, "keep-stage").writeText("uncommitted stage custody")
        LocalPackageInstallJournal.prepare(paths.stage)
        if (withPrevious) {
            assertTrue(paths.previous.parentFile!!.mkdirs() || paths.previous.parentFile!!.isDirectory)
            assertTrue(paths.final.copyRecursively(paths.previous))
            File(paths.previous, "keep-previous").writeText("previous custody")
            if (conflictingPrevious) {
                val marker = File(paths.previous, "DROIDE_ACTIVATION_ID")
                val otherGeneration = "00000000-0000-0000-0000-000000000001"
                assertNotEquals(File(paths.final, "DROIDE_ACTIVATION_ID").readText(), otherGeneration)
                marker.writeText(otherGeneration)
            }
        }
    }

    // BASELINE SEAM: exact current recovery composition used by the local installers.
    // This is deliberately not a claim that full provisioning/download/install was executed.
    // After baseline measurement, only this helper's ownedReceipt call is intended to be
    // wired to the approved production recovery preflight. All custody assertions stay fixed.
    private suspend fun recoverUsingCurrentInstallerComposition(observedAuthority: LocalManagedPackageAuthority,
        record: ManagedPackageRecord, kind: String = record.metadata.getValue(LocalManagedPackageMetadata.KIND_KEY),
    ): ManagedPackageRecord? = LocalPackageInstallJournal.transactionMutex.withLock {
        val paths = transaction(record, kind)
        val previous = observedAuthority.recoveryReceipt(record.familyId, record.version, kind)
        LocalPackageInstallJournal.recover(paths, previous?.let {
            LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
        })
        previous
    }

    private fun registryBytes(): String? =
        context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).getString("records_json", null)

    private fun custodyEvidence() = Triple(registryBytes(), noFollowBytes(File(root, "packages")), noFollowBytes(File(root, "managed")))

    private fun clearRetirementFixture() {
        check(!LocalPackageInstallJournal.transactionMutex.isLocked && !ManagedPackageMutationGate.mutex.isLocked)
        assertTrue(context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).edit().clear().commit())
        ownedFamilies.forEach { assertTrue(PathSecurity.deleteTreeNoFollow(File(root, "packages/$it"))) }
        listOf("managed", "packages/.staging", "packages/.previous", "packages/.trash/local-authority", "packages/.trash/cli.jq-Alpine_3.24",
            "r37-root-alias", "r37-outside-sentinel").forEach { assertTrue(PathSecurity.deleteTreeNoFollow(File(root, it))) }
        R37AllowlistedLocalShell.expect(emptyList())
    }

    private fun retirementEvidence(): Pair<String?, Map<String, Pair<String, List<Byte>>>> =
        context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).getString("records_json", null) to
            noFollowBytes(File(root, "packages"))

    private fun noFollowBytes(directory: File): Map<String, Pair<String, List<Byte>>> {
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return emptyMap()
        val out = linkedMapOf<String, Pair<String, List<Byte>>>()
        Files.walk(directory.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                check(out.size < 256) { "Fixture snapshot exceeded its entry bound" }
                val path = iterator.next()
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val value = when {
                    attributes.isSymbolicLink -> "link" to Files.readSymbolicLink(path).toString().toByteArray().toList()
                    attributes.isDirectory -> "directory" to emptyList()
                    attributes.isRegularFile -> {
                        check(attributes.size() <= 16_384) { "Fixture file exceeded its snapshot bound" }
                        "file" to Files.readAllBytes(path).toList()
                    }
                    else -> error("Unsupported fixture node")
                }
                out[directory.toPath().relativize(path).toString()] = value
            }
        }
        return out
    }

    private suspend fun assertRetainedConflict(): IllegalStateException {
        val before = retirementEvidence()
        R37AllowlistedLocalShell.expect(emptyList())
        val failure = runCatching { authority.reconcile() }.exceptionOrNull()
        R37AllowlistedLocalShell.assertNoAttempts()
        assertTrue("Custody conflict must be explicit", failure is IllegalStateException)
        assertEquals(before, retirementEvidence())
        return failure as IllegalStateException
    }

    private fun hostShim(target: String): String =
        "#!/system/bin/sh\nexec /system/bin/sh ${LocalExecutionSubstrate.shellQuote(target)} \"${'$'}@\"\n"

    private suspend fun rawCurrent(version: String, active: Boolean): ManagedPackageRecord {
        val install = File(root, "packages/cli.r37-raw/$version/arm64-v8a").apply { assertTrue(mkdirs()) }
        val activation = LocalPackageInstallJournal.prepare(install)
        // Small ELF header/interpreter fixture only. It contains no runnable program.
        val interpreter = LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER.toByteArray(Charsets.US_ASCII) + 0.toByte()
        val bytes = ByteArray(120 + interpreter.size)
        byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1).copyInto(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(16, 2.toShort()); putShort(18, 183.toShort()); putInt(20, 1)
            putLong(32, 64); putShort(52, 64.toShort()); putShort(54, 56.toShort()); putShort(56, 1.toShort())
            putInt(64, 3); putLong(72, 120); putLong(96, interpreter.size.toLong())
        }
        interpreter.copyInto(bytes, 120)
        File(install, "payload/bin/raw-tool").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        write(install, "payload/host-bin/raw-tool", "#!/system/bin/sh\n# Synthetic fixture, never executed.\nexit 0\n")
        LocalPackageIntegrity.seal(install, maxFiles = 8, maxBytes = 8192)
        val artifact = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ManagedPackageRecord("cli.r37-raw", version, ExecutionScope.LOCAL_LINUX_ARM64.name, install.path,
            version.toLong(), active, pathEntries = listOf("${install.path}/payload/host-bin"),
            commands = mapOf("raw-tool" to "${install.path}/payload/host-bin/raw-tool"),
            healthChecks = listOf(ManagedPackageHealthCheck("host-bin/raw-tool", listOf("--version"))),
            artifactSha256 = artifact, abi = "arm64-v8a", metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
                LocalManagedPackageMetadata.COMMAND_KEY to "raw-tool",
                LocalManagedPackageMetadata.ELF_INTERPRETER_KEY to LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER,
                LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activation))
    }

    private fun rawExecutable(record: ManagedPackageRecord) = R37ShellCall(
        "test -x ${LocalExecutionSubstrate.shellQuote(record.commands.getValue("raw-tool"))}", 1_048_576, 120_000)

    private fun removalBackup(record: ManagedPackageRecord): File {
        val trash = File(root, "packages/.trash/local-authority/${LocalPackageRemovalJournal.entryName(record)}")
        assertTrue(trash.parentFile!!.mkdirs() || trash.parentFile!!.isDirectory)
        LocalPackageRemovalJournal.prepare(trash, record)
        assertTrue(File(record.installRoot).copyRecursively(trash))
        assertTrue(LocalPackageRemovalJournal.matchesIntent(requireNotNull(LocalPackageRemovalJournal.read(trash)), trash))
        return trash
    }

    private fun treeBytes(directory: File): Map<String, Pair<Boolean, List<Byte>>> {
        assertTrue("Missing fixture tree: $directory", directory.isDirectory)
        return directory.walkTopDown().associate { file ->
            assertFalse("Unexpected symbolic link: $file", PathSecurity.isSymbolicLink(file))
            file.relativeTo(directory).invariantSeparatorsPath to
                (file.isDirectory to if (file.isDirectory) emptyList() else file.readBytes().toList())
        }
    }

    private suspend fun rejected(record: ManagedPackageRecord) {
        R37AllowlistedLocalShell.expect(emptyList())
        assertFalse("Unexpected acceptance of ${record.familyId}@${record.version}: ${record.metadata}", authority.verify(record))
        R37AllowlistedLocalShell.assertConsumed(emptyList())
    }

    private suspend fun ubuntu(family: String): Pair<ManagedPackageRecord, ManagedPackageRecord> {
        val recipe = requireNotNull(UbuntuAptCatalog.find(family))
        val command = recipe.commands.keys.single()
        val layerFamily = "runtime.ubuntu-deps.r37-$command"
        val layerRoot = File(root, "packages/$layerFamily/${FoundryUbuntuGuestEnvironmentSpec.LAYER_RECORD_VERSION}/arm64-v8a")
        assertTrue(layerRoot.mkdirs())
        write(layerRoot, "LAYER.txt", "schema=1\nkind=ubuntu-apt-layer\npackages=${recipe.packages.joinToString(",")}\n")
        write(layerRoot, "payload/verify-layer", "#!/system/bin/sh\n# Synthetic fixture, never executed.\nexit 0\n")
        LocalPackageIntegrity.seal(layerRoot, maxFiles = 4, maxBytes = 8192)
        val layer = ManagedPackageRecord(layerFamily, FoundryUbuntuGuestEnvironmentSpec.LAYER_RECORD_VERSION,
            ExecutionScope.LOCAL_LINUX_ARM64.name, layerRoot.path, 1,
            healthChecks = listOf(ManagedPackageHealthCheck("verify-layer", emptyList())),
            metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to recipe.packages.sorted().joinToString(",")))
        registry.put(layer)
        val packageRoot = File(root, "packages/$family/${recipe.version}/arm64-v8a")
        assertTrue(packageRoot.mkdirs())
        val activationId = LocalPackageInstallJournal.prepare(packageRoot)
        write(packageRoot, "payload/bin/$command", LocalUbuntuAptIntegration.guestWrapper(recipe, command))
        write(packageRoot, "payload/host-bin/$command", LocalUbuntuAptIntegration.hostWrapper(recipe, command,
            "${root.path}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"))
        write(packageRoot, "APT_PACKAGES.txt", recipe.packages.sorted().joinToString("\n", postfix = "\n"))
        val seal = LocalPackageIntegrity.seal(packageRoot, maxFiles = 8, maxBytes = 8192)
        val record = ManagedPackageRecord(family, recipe.version, ExecutionScope.LOCAL_LINUX_ARM64.name,
            packageRoot.path, 1, pathEntries = listOf("${packageRoot.path}/payload/host-bin"),
            commands = mapOf(command to "${packageRoot.path}/payload/host-bin/$command"),
            dependencies = listOf("${layer.familyId}@${layer.version}"),
            healthChecks = listOf(ManagedPackageHealthCheck("host-bin/$command", recipe.probeArgs(command))),
            artifactSha256 = seal, abi = "arm64-v8a", metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_APT,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal))
        return record to layer
    }

    private suspend fun legacy(): ManagedPackageRecord {
        val recipe = requireNotNull(WorkstationGuestPackageCatalog.find("cli.jq", WorkstationGuestEnvironmentSpec.VERSION))
        val versionPath = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val packageRoot = File(root, "packages/cli.jq/$versionPath/arm64-v8a")
        assertTrue(packageRoot.mkdirs())
        write(packageRoot, "payload/bin/jq", "#!/system/bin/sh\n# Synthetic legacy fixture, never executed.\nexit 0\n")
        LocalPackageIntegrity.seal(packageRoot, maxFiles = 4, maxBytes = 8192)
        return ManagedPackageRecord(recipe.familyId, recipe.version, ExecutionScope.LOCAL_LINUX_ARM64.name,
            packageRoot.path, 1, pathEntries = listOf("${packageRoot.path}/payload/bin"),
            commands = mapOf("jq" to "${packageRoot.path}/payload/bin/jq"),
            healthChecks = recipe.requiredManagedHealthChecks(), abi = "arm64-v8a",
            metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name) +
                recipe.admissionContractSha256()?.let { mapOf(WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY to it) }.orEmpty())
    }

    private fun write(directory: File, relative: String, text: String) {
        File(directory, relative).apply { parentFile!!.mkdirs(); writeText(text); assertTrue(setExecutable(true, true)) }
    }

    private fun health(record: ManagedPackageRecord): R37ShellCall {
        val check = record.healthChecks.single()
        val command = (listOf("/system/bin/sh", "${record.installRoot}/payload/${check.executable}") + check.args)
            .joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
        return R37ShellCall(command, 128_000, 60_000)
    }

    private fun legacyExecutable(record: ManagedPackageRecord) = R37ShellCall(
        "test -x ${LocalExecutionSubstrate.shellQuote(record.commands.getValue("jq"))}", 1_048_576, 120_000)
}

data class R37ShellCall(val command: String, val maxOutputBytes: Int, val timeoutMs: Long, val exitCode: Int = 0)

@Implements(value = LocalExecutionSubstrate::class, isInAndroidSdk = false)
class R37AllowlistedLocalShell {
    @Implementation
    suspend fun shellBounded(command: String, maxOutputBytes: Int, timeoutMs: Long,
        onOutput: ((String) -> Unit)?, keepTail: Boolean): ExecResult {
        attempt(command, maxOutputBytes, timeoutMs)
        check(onOutput == null && !keepTail) { "Unexpected shell streaming mode" }
        return consume(command, maxOutputBytes, timeoutMs)
    }

    @Implementation
    suspend fun shell(command: String): ExecResult {
        attempt(command, 1_048_576, 120_000)
        return consume(command, 1_048_576, 120_000)
    }

    companion object {
        private val expected = ArrayDeque<R37ShellCall>()
        private val seen = mutableListOf<R37ShellCall>()
        private val attempted = mutableListOf<R37ShellCall>()
        @Synchronized fun expect(calls: List<R37ShellCall>) {
            expected.clear(); expected.addAll(calls); seen.clear(); attempted.clear()
        }
        @Synchronized private fun attempt(command: String, maxOutputBytes: Int, timeoutMs: Long) {
            attempted += R37ShellCall(command, maxOutputBytes, timeoutMs)
        }
        @Synchronized fun assertAttempts(calls: List<R37ShellCall>) {
            assertEquals(calls.map { it.copy(exitCode = 0) }, attempted)
        }
        @Synchronized fun assertNoAttempts() { assertTrue("Forbidden shell attempts: $attempted", attempted.isEmpty()) }
        @Synchronized fun assertConsumed(calls: List<R37ShellCall>) {
            assertAttempts(calls)
            assertEquals(calls, seen)
            assertTrue("Missing expected fake shell calls: $expected", expected.isEmpty())
        }
        @Synchronized private fun consume(command: String, maxOutputBytes: Int, timeoutMs: Long): ExecResult {
            val call = expected.pollFirst() ?: throw AssertionError("Unexpected shell call: $command")
            assertEquals(call.command, command)
            assertEquals(call.maxOutputBytes, maxOutputBytes)
            assertEquals(call.timeoutMs, timeoutMs)
            seen += call
            return ExecResult(call.exitCode, "r37 fake shell", false)
        }
    }
}

/** Secondary fail-closed boundary: a missed shell shadow must never launch a real process. */
@Implements(value = LocalProcessSupervisor::class, isInAndroidSdk = false, callThroughByDefault = false)
class R37NoLocalProcess {
    @Implementation
    suspend fun capture(argv: List<String>, cwd: File, environment: Map<String, String>, maxOutputBytes: Int,
        timeoutMs: Long, input: ((OutputStream) -> Unit)?, cleanup: (() -> Unit)?, onOutput: ((String) -> Unit)?,
        keepTail: Boolean, streamInput: ProcessInputBuffer?): ExecResult =
        throw AssertionError("Real process route forbidden in jq dispatch regression: $argv")

    @Implementation
    fun terminate(process: Process) { throw AssertionError("Real process cleanup forbidden in jq dispatch regression") }
}
