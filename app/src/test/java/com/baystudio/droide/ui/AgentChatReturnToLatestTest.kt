package com.baystudio.droide.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.ApplicationWorkAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// Keep prefetch deadlines on Robolectric's virtual frame clock.
@Config(sdk = [35], application = DroideApplication::class, qualifiers = "w360dp-h640dp",
    instrumentedPackages = ["androidx.compose.foundation.lazy.layout"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatReturnToLatestTest {
    private val durationScale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float get() = durationScale.value
    })
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val ui by lazy { AgentWorkspaceUiState(app.workspaceHost.runtimeScope, app) }
    private lateinit var viewport: AgentChatViewport
    private lateinit var scope: CoroutineScope
    private val requests = mutableListOf<Job>()
    private var rowCount by mutableIntStateOf(60)
    private var tailHeight by mutableStateOf(70.dp)
    private var height by mutableStateOf(360.dp)
    private var session by mutableStateOf("first")
    private var visible by mutableStateOf(true)

    @Before fun openSession() {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
    }

    @After fun closeSession() { ui.close(); app.workspaceHost.close() }

    private fun mount() {
        compose.setContent { DroideTheme {
            scope = rememberCoroutineScope()
            if (visible) {
                val key = AgentChatConversationKey("droide", session)
                val count = rowCount
                val keys = (0 until count).map { "row:$it" }
                viewport = rememberAgentChatViewport(ui, key, ui.chatPosition(key), keys,
                    AgentChatActivity(count, 0, 0, 0, 0, 0, 0, null, false), false)
                val density = LocalDensity.current
                val extra = with(density) { viewport.disclosureSpacePx.toDp() }
                SideEffect { viewport.measuredDisclosurePadding(with(density) { 16.dp.roundToPx() }, viewport.disclosureSpacePx) }
                Column {
                    LazyColumn(Modifier.width(360.dp).height(height).testTag("transcript"),
                        state = viewport.listState, contentPadding = PaddingValues(bottom = 16.dp + extra)) {
                        items(count, key = { keys[it] }) { index ->
                            Text("Row $index", Modifier.fillMaxWidth().height(if (index == count - 1) tailHeight else 70.dp))
                        }
                    }
                    Button(onClick = { requests += scope.launch { viewport.returnToLatest() } }) { Text("Latest") }
                }
            } else Text("Editor")
        } }
        compose.waitUntil(5_000) { compose.waitForIdle(); ::viewport.isInitialized && viewport.ready }
    }

    private fun detach(index: Int = 0) {
        compose.onNodeWithTag("transcript").performScrollToIndex(index)
        compose.waitForIdle()
        assertFalse(viewport.following)
    }

    private fun start(): Job {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Latest").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return requests.last()
    }

    private fun assertAtTail() {
        compose.waitForIdle()
        assertFalse(viewport.listState.canScrollForward)
        assertEquals(rowCount - 1, viewport.listState.layoutInfo.visibleItemsInfo.last().index)
        assertTrue(viewport.following)
        assertEquals(0, viewport.unread)
        assertEquals(0, viewport.appScrolls)
    }

    @Test fun nearbyReturnMovesBetweenFramesAndPreservesListBounds() {
        mount()
        compose.runOnIdle { scope.launch { viewport.listState.scrollBy(-30f) } }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot
        val before = viewport.listState.firstVisibleItemScrollOffset
        val job = start()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertTrue(job.isActive)
        assertTrue(viewport.listState.canScrollForward)
        assertTrue(viewport.listState.firstVisibleItemScrollOffset > before)
        assertEquals(bounds, compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(240)
        assertTrue(job.isCompleted)
        assertAtTail()
    }

    @Test fun longHistoryFinishesInOneShortAnimationAndRepeatedTapsDoNotRestartIt() {
        rowCount = 1_000
        mount(); detach()
        val job = start()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertTrue(job.isActive)
        assertTrue(viewport.listState.firstVisibleItemIndex > 980)
        compose.onNodeWithText("Latest").performClick()
        compose.mainClock.advanceTimeBy(180)
        compose.waitForIdle()
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertAtTail()
    }

    @Test fun aTallFinalMessageAlignsItsBottom() {
        tailHeight = 1_400.dp
        mount(); detach()
        start()
        compose.mainClock.advanceTimeBy(280)
        assertAtTail()
        assertEquals(rowCount - 1, viewport.listState.firstVisibleItemIndex)
        assertTrue(viewport.listState.firstVisibleItemScrollOffset > 0)
    }

    @Test fun reducedMotionReturnsImmediately() {
        mount(); detach()
        durationScale.value = 0f
        val job = start()
        assertTrue(job.isCompleted)
        assertAtTail()
    }

    @Test fun newOutputDuringReturnIsVisibleWhenTheMotionEnds() {
        mount(); detach()
        val job = start()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { rowCount += 3 }
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        assertTrue(job.isCompleted)
        assertAtTail()
        compose.onNodeWithText("Row ${rowCount - 1}").assertIsDisplayed()
    }

    @Test fun keyboardSizedResizeDuringReturnKeepsTheTailAligned() {
        mount(); detach()
        start()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { height = 190.dp }
        compose.mainClock.advanceTimeBy(240)
        assertAtTail()
    }

    @Test fun aDragCancelsReturnAndLaterOutputDoesNotPullTheReaderBack() {
        mount(); detach()
        val job = start()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("transcript").performTouchInput {
            down(center)
            moveBy(Offset(0f, 100f), delayMillis = 32)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(job.isCancelled)
        assertFalse(viewport.following)
        compose.onNodeWithTag("transcript").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val before = viewport.position()!!
        compose.runOnIdle { rowCount++ }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertFalse(viewport.following)
        assertEquals(before.anchorKey, viewport.position()!!.anchorKey)
        assertEquals(before.offset, viewport.position()!!.offset)
        assertEquals(0, viewport.appScrolls)
    }

    @Test fun openingDetailsCancelsTheReturnAndHoldsTheReader() {
        mount(); detach()
        val job = start()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { viewport.beginDisclosure() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue(job.isCancelled)
        assertFalse(viewport.following)
        assertEquals(0, viewport.appScrolls)
    }

    @Test fun changingSessionCancelsTheOldMotionAndRestoresWithoutAnimation() {
        mount(); detach()
        val old = viewport
        val job = start()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { session = "second" }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        assertTrue(job.isCancelled)
        assertNotSame(old, viewport)
        assertEquals(0, old.appScrolls)
        assertAtTail()
    }

    @Test fun hidingThePaneCancelsItsMotionAndRestoresTheReadingAnchor() {
        mount(); detach()
        val job = start()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(job.isCancelled)
        val saved = ui.chatPosition(AgentChatConversationKey("droide", session))!!
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        assertEquals(saved.anchorKey, viewport.position()!!.anchorKey)
        assertEquals(saved.offset, viewport.position()!!.offset)
        assertFalse(viewport.following)
    }
}
