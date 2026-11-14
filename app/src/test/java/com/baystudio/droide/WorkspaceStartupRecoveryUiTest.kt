package com.baystudio.droide

import android.app.Application
import android.os.Process
import org.robolectric.shadows.ShadowProcess
import org.junit.Before
import org.junit.After
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.core.*
import com.baystudio.droide.ui.ExtensionsPane
import com.baystudio.droide.ui.workspaceAgentPluginsForStartup
import com.baystudio.droide.ui.rememberWorkspaceAgentAvailability
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceStartupRecoveryUiTest {
    private var previousPid = 0
    @Before fun prepareAndroidProcessIdentity() {
        previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
    }
    @After fun restoreAndroidProcessIdentity() { ShadowProcess.setPid(previousPid) }

    @get:Rule val compose = createComposeRule()

    @Test fun failureOffersTwoExplicitActionsAndExplainsTemporaryLimitations() {
        var normal = 0; var builtins = 0
        compose.setContent { MaterialTheme {
            WorkspaceStartupFailureContent("Extension verification failed", { normal++ }, { builtins++ })
        } }
        compose.runOnIdle { assertEquals(0, normal); assertEquals(0, builtins) }
        compose.onNodeWithText("Extension verification failed").assertIsDisplayed()
        compose.onNodeWithText("Installed extensions, language servers and automatic tool setup", substring = true).assertIsDisplayed()
        compose.onNodeWithText("will not be deleted", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restart", substring = true, ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Retry normal startup").performClick()
        compose.runOnIdle { assertEquals(1, normal); assertEquals(0, builtins) }
        compose.onNodeWithText("Open temporarily with built-ins").performClick()
        compose.runOnIdle { assertEquals(1, normal); assertEquals(1, builtins) }
    }

    @Test fun recoveryPaneExplainsReasonWithoutMarketplaceOrLifecycleRefreshAccess() {
        val root = Files.createTempDirectory("recovery-pane-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val project = File(root, "projects/fixture").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var ownedBridge: DeviceBridgeManager? = null
        var ownedCapabilities: UniversalCapabilityRegistry? = null
        var ownedManager: DevelopmentExtensionsManager? = null
        try {
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                discovery = { error("No discovery") },
            ))
            ownedBridge = bridge
            val development = AndroidDevelopmentManager(context, project, bridge)
            val capabilities = UniversalCapabilityRegistry("fixture", project)
            ownedCapabilities = capabilities
            var starts = 0; var refreshes = 0
            val manager = DevelopmentExtensionsManager(context, project, development, bridge, capabilities,
                ManagedPackageRegistry(context), false, { _, _, _ -> starts++ }, { refreshes++ })
            ownedManager = manager
            compose.setContent { MaterialTheme { ExtensionsPane(manager, development, bridge, "a.kt") } }
            compose.onNodeWithText(TEMPORARY_COMPONENTS_UNAVAILABLE, substring = true).assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, starts); assertEquals(0, refreshes) }
        } finally {
            try { ownedManager?.close(); ownedCapabilities?.close(); ownedBridge?.close() }
            finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
        }
    }
    @Test fun agentPaneHostSelectionSkipsAutomaticCliProbeInRecoveryAndKeepsNormalProbe() {
        val root = Files.createTempDirectory("recovery-agent-pane-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var ownedBridge: DeviceBridgeManager? = null
        var ownedHost: WorkspaceAgentPluginHost? = null
        try {
            val project = File(root, "projects/fixture").apply { mkdirs() }
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                discovery = { error("No discovery") },
            ))
            ownedBridge = bridge
            var resolutions = 0; var starts = 0
            val processHost = object : StdioProcessHost {
                override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
                override suspend fun resolveExecutable(command: String): String? {
                    resolutions++
                    return "/fixture/bin/$command"
                }
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    starts++; error("No processes permitted")
                }
            }
            val host = WorkspaceAgentPluginHost(project, context.cacheDir, FileRepository(project), processHost,
                AndroidDevelopmentManager(context, project, bridge), bridge, ApprovalManager(), PermissionEngine(), scope)
            ownedHost = host
            assertSame(host, workspaceAgentPluginsForStartup(true, host))
            assertNull(workspaceAgentPluginsForStartup(false, host))
            var enabled by mutableStateOf(false)
            var expanded by mutableStateOf(false)
            compose.setContent {
                val selected = workspaceAgentPluginsForStartup(enabled, host)
                // Same nullable host and lifecycle observer used by the actual AgentPane callsite.
                val availability = rememberWorkspaceAgentAvailability(selected, expanded) { selected?.availability().orEmpty() }
                androidx.compose.material3.Text("${availability.size} available Agent entries")
            }
            compose.runOnIdle { assertEquals(0, resolutions); assertEquals(0, starts); expanded = true }
            compose.runOnIdle { assertEquals(0, resolutions); assertEquals(0, starts); enabled = true }
            compose.runOnIdle { assertEquals(7, resolutions); assertEquals(0, starts); expanded = false }
            compose.runOnIdle { assertEquals(7, resolutions); enabled = false }
            compose.runOnIdle { assertEquals(7, resolutions); expanded = true }
            compose.runOnIdle { assertEquals(7, resolutions); assertEquals(0, starts) }
        } finally {
            try { ownedHost?.close(); ownedBridge?.close() }
            finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
        }
    }

}
