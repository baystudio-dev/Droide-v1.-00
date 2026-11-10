package com.baystudio.droide.ui

import org.junit.Before

import com.baystudio.droide.core.ApplicationWorkAdmission

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Compose observation tests. Requires the project's Android/Compose test dependencies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImeObservationTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val host get() = app.workspaceHost
    private val ui by lazy { AgentWorkspaceUiState(host.runtimeScope, app) }

    @After fun cleanup() { ui.close(); host.close() }

    @Test fun positiveImePixelsDoNotRecomposeVisibilityConsumers() {
        val bottom = mutableIntStateOf(1)
        val insets = object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getTop(density: Density) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getBottom(density: Density) = bottom.intValue
        }
        var compositions = 0
        var present = false
        compose.setContent {
            val value = rememberImeBottomPresent(insets)
            SideEffect { compositions++; present = value }
        }
        compose.waitForIdle()
        val initial = compositions
        for (pixels in listOf(5, 60, 120, 300, 200, 50, 1)) {
            compose.runOnIdle { bottom.intValue = pixels }
            compose.waitForIdle()
            assertTrue(present)
            assertEquals(initial, compositions)
        }
        compose.runOnIdle { bottom.intValue = 0 }
        compose.waitForIdle()
        assertFalse(present)
        assertTrue(compositions > initial)
        compose.runOnIdle { bottom.intValue = 300 }
        compose.waitForIdle()
        assertTrue(present)
    }

    @Test fun layoutPhaseResizeFollowsTailWithoutRecomposingObserverOwner() {
        val heightDp = mutableIntStateOf(400)
        val keys = (0..59).map { "row-$it" }
        val activity = AgentChatActivity(60, 0, 0, 0, 0, 0, 0, null, false)
        lateinit var viewport: AgentChatViewport
        var compositions = 0
        compose.setContent {
            DroideTheme {
                val current = rememberAgentChatViewport(ui, AgentChatConversationKey("ime", "follow"), null, keys, activity, false)
                SideEffect { viewport = current; compositions++ }
                LazyColumn(state = current.listState, contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.width(360.dp).layout { measurable, constraints ->
                        val h = heightDp.intValue.dp.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
                        val child = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(child.width, child.height) { child.placeRelative(0, 0) }
                    }) {
                    itemsIndexed(keys, key = { _, key -> key }) { index, _ -> Text("Result $index", Modifier.height(30.dp)) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); viewport.ready && !viewport.listState.canScrollForward }
        val initial = compositions
        for (height in (400 downTo 180 step 20) + (180..400 step 20)) {
            compose.runOnIdle { heightDp.intValue = height }
            compose.waitUntil(5_000) { compose.waitForIdle(); !viewport.listState.canScrollForward }
            assertTrue(viewport.following)
            assertEquals(0, viewport.unread)
        }
        assertEquals("Geometry belongs to the observer/layout, not its composition", initial, compositions)
    }

    @Test fun readingAnchorSurvivesResizeWithNewOutputAndLatestStillWorks() {
        val height = mutableIntStateOf(400)
        val keys = mutableStateOf((0..59).map { "row-$it" })
        lateinit var viewport: AgentChatViewport
        lateinit var scope: CoroutineScope
        compose.setContent {
            DroideTheme {
                val rows = keys.value
                val activity = AgentChatActivity(rows.size, 0, 0, 0, 0, 0, 0, null, false)
                val current = rememberAgentChatViewport(ui, AgentChatConversationKey("ime", "reading"), null, rows, activity, false)
                val currentScope = rememberCoroutineScope()
                SideEffect { viewport = current; scope = currentScope }
                LazyColumn(state = current.listState, modifier = Modifier.width(360.dp).height(height.intValue.dp)) {
                    itemsIndexed(rows, key = { _, key -> key }) { index, _ -> Text("Result $index", Modifier.height(30.dp)) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); viewport.ready }
        compose.runOnIdle { scope.launch { viewport.listState.scrollToItem(20, 7) } }
        compose.waitUntil(5_000) { compose.waitForIdle(); !viewport.following }
        val before = viewport.position()!!
        for (h in listOf(300, 180, 240, 400)) {
            compose.runOnIdle { height.intValue = h; keys.value = keys.value + "row-${keys.value.size}" }
            compose.waitForIdle()
            assertEquals(before.anchorKey, viewport.position()!!.anchorKey)
            assertEquals(before.offset, viewport.position()!!.offset)
            assertFalse(viewport.following)
        }
        assertEquals(4, viewport.unread)
        compose.runOnIdle { scope.launch { viewport.latest() } }
        compose.waitUntil(5_000) { compose.waitForIdle(); viewport.following && !viewport.listState.canScrollForward }
        assertEquals(0, viewport.unread)
    }

    @Test fun activityPublishedBeforeNewRowMeasurementStillFollowsTheMeasuredRow() {
        val keys = mutableStateOf((0..59).map { "row-$it" })
        val count = mutableIntStateOf(60)
        lateinit var viewport: AgentChatViewport
        compose.setContent {
            DroideTheme {
                val activity = AgentChatActivity(count.intValue, 0, 0, 0, 0, 0, 0, null, false)
                val current = rememberAgentChatViewport(ui, AgentChatConversationKey("ime", "late-layout"), null, keys.value, activity, false)
                SideEffect { viewport = current }
                LazyColumn(state = current.listState, modifier = Modifier.width(360.dp).height(240.dp)) {
                    itemsIndexed(keys.value, key = { _, key -> key }) { index, _ -> Text("Result $index", Modifier.height(30.dp)) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); viewport.ready && !viewport.listState.canScrollForward }
        compose.runOnIdle { count.intValue = 61 }
        compose.waitForIdle()
        compose.runOnIdle { keys.value = keys.value + "row-60" }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            viewport.listState.layoutInfo.totalItemsCount == 61 && !viewport.listState.canScrollForward
        }
        assertTrue(viewport.following)
        assertEquals(0, viewport.unread)
    }
}
