package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.scrollBy
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.ApplicationWorkAdmission
import kotlinx.coroutines.launch
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=DroideApplication::class, qualifiers="w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatDisclosureJumpTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val host get() = app.workspaceHost
    private val ui by lazy { AgentWorkspaceUiState(host.runtimeScope, app) }
    private lateinit var scope: kotlinx.coroutines.CoroutineScope
    private lateinit var viewport: AgentChatViewport
    private var viewportHeight by mutableStateOf(500.dp)
    private var basePadding by mutableStateOf(12.dp)
    private var tailHeight by mutableStateOf(400.dp)
    private val activity = AgentChatActivity(21,0,0,0,0,0,0,null,false)
    @Before fun before() {
        ApplicationWorkAdmission.initialize(app); ApplicationWorkAdmission.reopenFromUser()
    }
    @After fun after() { ui.close(); host.close() }
    private fun mount() {
        compose.setContent { DroideTheme {
            scope = rememberCoroutineScope()
            viewport=rememberAgentChatViewport(ui,AgentChatConversationKey("droide","disclosure-jump"),null,
                (0..20).map { "row:$it" }, activity,false)
            val density=LocalDensity.current
            val space=with(density) { viewport.disclosureSpacePx.toDp() }.coerceAtMost(viewportHeight)
            SideEffect { viewport.measuredDisclosurePadding(with(density) { basePadding.roundToPx() }, with(density) { space.roundToPx() }) }
            LazyColumn(Modifier.width(360.dp).height(viewportHeight).testTag("list"),
                state=viewport.listState,contentPadding=PaddingValues(bottom=basePadding+space)) {
                items(20,key={ "row:$it" }) { Text("Message $it",Modifier.height(60.dp)) }
                item(key="row:20") { Text("Terminal output",Modifier.height(tailHeight)) }
            }
        } }
        compose.waitUntil(5000) { compose.waitForIdle(); viewport.ready }
    }
    private fun collapse() {
        compose.runOnIdle { viewport.beginDisclosure(); tailHeight=80.dp }
        compose.waitForIdle()
    }
    private fun beginDrag(): DragInteraction.Start {
        val start=DragInteraction.Start()
        compose.runOnIdle { (viewport.listState.interactionSource as MutableInteractionSource).tryEmit(start) }
        compose.waitUntil(5000) { compose.waitForIdle(); viewport.dragging }
        return start
    }
    @Test fun touchingAfterCollapseDoesNotJumpTheTranscript() {
        mount(); collapse()
        val before=viewport.position()!!
        val top=compose.onNodeWithText("Terminal output").fetchSemanticsNode().positionInRoot.y
        beginDrag()
        assertEquals(before.index,viewport.position()!!.index)
        assertEquals(before.offset,viewport.position()!!.offset)
        assertEquals(top,compose.onNodeWithText("Terminal output").fetchSemanticsNode().positionInRoot.y,1f)
        assertTrue(viewport.disclosureSpacePx>0)
        assertFalse(viewport.following)
    }
    @Test fun repeatedTouchesDoNotPayDownSpaceWithoutUserMovement() {
        mount(); collapse()
        repeat(4) {
            val before=viewport.position()!!
            val start=beginDrag()
            compose.runOnIdle { (viewport.listState.interactionSource as MutableInteractionSource).tryEmit(DragInteraction.Cancel(start)) }
            compose.waitForIdle()
            assertEquals(before.index,viewport.position()!!.index)
            assertEquals(before.offset,viewport.position()!!.offset)
        }
    }
    @Test fun scrollingTowardHistoryRetiresSpaceWithoutExtraMotion() {
        mount(); collapse(); beginDrag()
        compose.onNodeWithTag("list").performScrollToIndex(15)
        compose.waitForIdle()
        assertEquals(15,viewport.position()!!.index)
        assertEquals(0,viewport.position()!!.offset)
        assertFalse(viewport.following)
    }
    @Test fun touchingDuringCollapseDoesNotConsumeTheRemainingAnimationBudget() {
        mount()
        val motion=Any()
        compose.runOnIdle { viewport.beginDisclosure(); viewport.disclosureMotion(motion,true); tailHeight=280.dp }
        compose.waitForIdle()
        val before=viewport.position()!!
        beginDrag()
        compose.runOnIdle { tailHeight=80.dp; viewport.disclosureMotion(motion,false) }
        compose.waitForIdle()
        assertEquals(before.index,viewport.position()!!.index)
        assertEquals(before.offset,viewport.position()!!.offset)
        assertFalse(viewport.following)
    }
    @Test fun resizedViewportUsesAppliedPaddingNotTheOldReservation() {
        mount(); collapse()
        compose.runOnIdle { viewportHeight=240.dp; basePadding=8.dp }
        compose.waitForIdle()
        val before=viewport.position()!!
        beginDrag()
        assertEquals(before.index,viewport.position()!!.index)
        assertEquals(before.offset,viewport.position()!!.offset)
    }
    @Test fun explicitLatestClearsReservationAndFollowsTheRealTail() {
        mount(); collapse()
        compose.runOnIdle { scope.launch { viewport.latest() } }
        compose.waitForIdle()
        assertEquals(0,viewport.disclosureSpacePx)
        assertTrue(viewport.following)
        assertFalse(viewport.listState.canScrollForward)
    }
}

// Baseline adapter: old production has no geometry publication API.
@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "UNUSED_PARAMETER")
private fun AgentChatViewport.measuredDisclosurePadding(base: Int, extra: Int) = Unit

@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "UNUSED_PARAMETER")
private fun AgentChatViewport.disclosureMotion(owner: Any, active: Boolean) = Unit
