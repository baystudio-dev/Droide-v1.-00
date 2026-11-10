package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.*
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored/unrun: real pinned renderer/container, synthetic owner with no native process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeTerminalGeometryTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()
    private class Surface(val view: DroideNativeTerminalView, val session: TerminalSession,
        val root: FrameLayout, val client: DroideTerminalViewClient) {
        var measured: TerminalViewportGeometry? = null
        fun layout(width: Int, height: Int) {
            view.layoutParams = FrameLayout.LayoutParams(width,height)
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
            view.reportViewport()
        }
    }
    private fun surface(body: (Surface)->Unit) {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=DroideNativeTerminalView(activity.get())
        val model=DeviceTerminalEmulator({},80,24)
        val emulator=DeviceTerminalEmulator::class.java.getDeclaredField("emulator").let {
            it.isAccessible=true;it.get(model) as TerminalEmulator
        }
        val delegate=Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _,_,_->null } as TerminalSessionClient
        val session=TerminalSession("unused", "/", emptyArray(), emptyArray(), 100, delegate)
        TerminalSession::class.java.getDeclaredField("mEmulator").apply { isAccessible=true }.set(session,emulator)
        val client=DroideTerminalViewClient(activity.get(),{view.terminalView},inputViewProvider={view},
            onGeometryChanged=view::reportViewport)
        view.setTerminalViewClient(client);view.setTextSize(14)
        val root=FrameLayout(activity.get());root.addView(view,FrameLayout.LayoutParams(320,480))
        activity.get().setContentView(root)
        val s=Surface(view,session,root,client)
        view.viewportChanged={owner,value->assertSame(session,owner);s.measured=value}
        view.attachSession(session);s.layout(320,480)
        try { body(s) } finally { view.release();root.removeAllViews();activity.pause().stop().destroy() }
    }
    @Test fun measuredGridMatchesActualUpstreamGrid()=surface { s ->
        val m=requireNotNull(s.measured)
        assertEquals(m.expectedColumns,m.emulatorColumns);assertEquals(m.expectedRows,m.emulatorRows)
        assertEquals(s.view.terminalView.width,m.widthPixels)
    }
    @Test fun zoomOutReportsLargerGridAndSameOwner()=surface { s ->
        val before=requireNotNull(s.measured)
        s.client.onScale(0.5f);s.view.reportViewport()
        val after=requireNotNull(s.measured)
        assertTrue(after.expectedColumns>=before.expectedColumns)
        assertEquals(after.expectedColumns,after.emulatorColumns)
    }
    @Test fun imeSizedViewportChangesRowsWithoutChangingColumns()=surface { s ->
        val before=requireNotNull(s.measured);s.layout(320,240);val after=requireNotNull(s.measured)
        assertEquals(before.expectedColumns,after.expectedColumns);assertTrue(after.expectedRows<before.expectedRows)
    }
    @Test fun rotationRecomputesActualDimensions()=surface { s ->
        s.layout(640,240);val after=requireNotNull(s.measured)
        assertEquals(640,after.widthPixels);assertEquals(240,after.heightPixels)
        assertEquals(after.expectedColumns,after.emulatorColumns)
    }
    @Test fun zeroSizeDoesNotPublishInventedGrid()=surface { s ->
        s.measured=null;s.layout(0,0);assertNull(s.measured)
    }
    @Test fun releaseStopsMeasurementsAndQueuedPresentationWork()=surface { s ->
        s.view.release();s.measured=null;s.view.reportViewport()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));assertNull(s.measured)
    }
    @Test fun hiddenAndRestoredSurfaceKeepsSessionAndMeasuredFit()=surface { s ->
        s.view.visibility=View.GONE;s.view.visibility=View.VISIBLE;s.layout(320,480)
        assertSame(s.session,s.view.currentSession)
        assertEquals(s.measured?.expectedColumns,s.measured?.emulatorColumns)
    }
    @Test fun foreignReceiptDoesNotChangeMeasuredOwner()=surface { s ->
        s.view.updateGeometry(TerminalGeometrySnapshot(owner=Any(),receipt=TerminalResizeReceipt(1,1,90,30,90,30,80,24,2)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertSame(s.session,s.view.currentSession)
    }
}
