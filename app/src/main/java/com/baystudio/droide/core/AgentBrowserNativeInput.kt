package com.baystudio.droide.core

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Native input only. The caller checks document identity and authority on every dispatch. */
internal object AgentBrowserNativeInput {
    data class Point(val x: Float, val y: Float) {
        init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) { "Invalid browser coordinates" } }
    }
    fun point(result: JsonObject): Point {
        val point = result.getValue("point").jsonObject
        return Point(point.getValue("x").jsonPrimitive.float, point.getValue("y").jsonPrimitive.float)
    }

    data class KeyStroke(val code: Int, val modifiers: Int)
    fun key(raw: String): KeyStroke {
        val parts = raw.split('+')
        require(parts.size in 1..5 && parts.none { it.isBlank() }) { "Invalid browser key combination" }
        var modifiers = 0
        for (part in parts.dropLast(1)) modifiers = modifiers or when (part.lowercase()) {
            "ctrl", "control" -> KeyEvent.META_CTRL_ON
            "alt" -> KeyEvent.META_ALT_ON
            "shift" -> KeyEvent.META_SHIFT_ON
            "meta" -> KeyEvent.META_META_ON
            else -> error("Unsupported browser key modifier")
        }
        val name = parts.last().lowercase()
        require(!(modifiers and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0 && name in setOf("c", "x", "v")) &&
            !(modifiers and KeyEvent.META_SHIFT_ON != 0 && name == "delete")) {
            "Clipboard shortcuts are not browser-site permission; use type with the supplied text"
        }
        val code = when (name) {
            "enter" -> KeyEvent.KEYCODE_ENTER; "tab" -> KeyEvent.KEYCODE_TAB
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE; "space" -> KeyEvent.KEYCODE_SPACE
            "backspace" -> KeyEvent.KEYCODE_DEL; "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "arrowup", "up" -> KeyEvent.KEYCODE_DPAD_UP; "arrowdown", "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "arrowleft", "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "arrowright", "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> KeyEvent.KEYCODE_MOVE_HOME; "end" -> KeyEvent.KEYCODE_MOVE_END
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP; "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            else -> { require(name.length == 1 && name[0] in 'a'..'z' && modifiers != 0) { "Use type for text, or a supported navigation key" }
                KeyEvent.KEYCODE_A + (name[0] - 'a') }
        }
        return KeyStroke(code, modifiers)
    }

    fun press(view: WebView, stroke: KeyStroke) {
        check(view.requestFocus()) { "Browser could not receive keyboard focus" }
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, stroke.code, 0, stroke.modifiers)
        val up = KeyEvent(time, time, KeyEvent.ACTION_UP, stroke.code, 0, stroke.modifiers)
        val accepted = view.dispatchKeyEvent(down)
        view.dispatchKeyEvent(up)
        check(accepted) { "Browser did not accept the key" }
    }

    suspend fun gesture(view: WebView, from: Point, to: Point = from, durationMs: Long = 64,
        mouse: Boolean = false, hover: Boolean = false,
        dispatch: suspend (() -> Unit) -> Unit, cancel: suspend (() -> Unit) -> Unit) {
        val downTime = SystemClock.uptimeMillis()
        var width = 0; var height = 0; var pressed = false
        fun send(action: Int, point: Point): Boolean {
            if (width == 0) { width = view.width; height = view.height }
            check(action == MotionEvent.ACTION_CANCEL || width > 0 && height > 0 && view.width == width && view.height == height) { "Browser viewport changed during input" }
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = if (mouse || hover) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply {
                x = view.paddingLeft + point.x * (width - view.paddingLeft - view.paddingRight)
                y = view.paddingTop + point.y * (height - view.paddingTop - view.paddingBottom)
                pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || hover) 0f else 1f; size = 1f
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                0, if (mouse && action != MotionEvent.ACTION_UP) MotionEvent.BUTTON_PRIMARY else 0,
                1f, 1f, 0, 0, if (mouse || hover) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, 0)
            return try { if (hover) view.dispatchGenericMotionEvent(event) else view.dispatchTouchEvent(event) }
                finally { event.recycle() }
        }
        if (hover) { dispatch { check(send(MotionEvent.ACTION_HOVER_MOVE, from)) { "Browser did not accept hover" } }; return }
        try {
            dispatch {
                if (from != to) {
                    val dx = (to.x - from.x) * (view.width - view.paddingLeft - view.paddingRight)
                    val dy = (to.y - from.y) * (view.height - view.paddingTop - view.paddingBottom)
                    val minimum = ViewConfiguration.get(view.context).scaledTouchSlop * 2f
                    require(dx * dx + dy * dy >= minimum * minimum) { "Gesture is too small; choose a larger target to avoid an unintended click" }
                }
                check(view.requestFocus()) { "Browser could not receive input focus" }; pressed = true
                check(send(MotionEvent.ACTION_DOWN, from)) { "Browser did not accept pointer down" } }
            val steps = if (from == to) 1 else (durationMs / 16).toInt().coerceIn(2, 60)
            for (step in 1..steps) {
                delay(durationMs / steps)
                if (from != to) dispatch {
                    val fraction = step.toFloat() / steps
                    check(send(MotionEvent.ACTION_MOVE, Point(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction))) {
                        "Browser did not accept pointer movement"
                    }
                }
            }
            dispatch { val accepted = send(MotionEvent.ACTION_UP, to); pressed = false
                check(accepted) { "Browser did not accept pointer up" } }
        } finally {
            if (pressed) withContext(NonCancellable) {
                // Cancel a partly delivered gesture; never replay it on a replacement document.
                runCatching { cancel { send(MotionEvent.ACTION_CANCEL, from) } }
            }
        }
    }
}
