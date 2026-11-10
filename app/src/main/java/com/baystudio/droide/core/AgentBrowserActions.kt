package com.baystudio.droide.core

import android.os.SystemClock
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** One operation. Retries only preparation; a dispatched pointer/key is never automatically replayed. */
internal class AgentBrowserActions(
    private val view: WebView,
    private val evaluate: suspend (String) -> String,
    private val dispatch: suspend (() -> Unit) -> Unit,
    private val cancel: suspend (() -> Unit) -> Unit,
) {
    suspend fun execute(operation: String, target: String, text: String?, submit: Boolean, options: BrowserActionOptions): JsonObject {
        val key = if (operation == "key") AgentBrowserNativeInput.key(requireNotNull(options.keyStroke) { "key is required" })
            else if (submit) AgentBrowserNativeInput.key("Enter") else null
        require(!submit || operation == "type") { "submit is supported only for type" }
        require(operation != "drag" || !options.endTarget.isNullOrBlank()) { "drag requires end_target" }
        require(operation != "swipe" || text in setOf("up", "down", "left", "right")) { "swipe requires up/down/left/right" }
        suspend fun run(op: String, selector: String): JsonObject = AgentBrowserInteraction.result(evaluate(
            AgentBrowserInteraction.script(op, selector, text, submit, options.checked, options.optionIndex)))
        suspend fun prepare(op: String, selector: String): JsonObject {
            val deadline = SystemClock.uptimeMillis() + options.timeoutMs
            var previous: JsonObject? = null
            var pinned = selector
            while (true) {
                val result = run(op, pinned)
                val ok = result.ok()
                if (ok && (!result.flag("prepared") || result["point"] == null)) return result
                if (ok) {
                    pinned = result.getValue("target").jsonPrimitive.content
                    val point = AgentBrowserNativeInput.point(result)
                    val before = previous?.let(AgentBrowserNativeInput::point)
                    if (before != null && kotlin.math.abs(before.x - point.x) < 0.0005f &&
                        kotlin.math.abs(before.y - point.y) < 0.0005f && previous?.get("bounds") == result["bounds"])
                        return result
                    previous = result
                } else {
                    previous = null
                    if (!result.flag("retryable")) return result
                }
                if (SystemClock.uptimeMillis() >= deadline) return if (!ok) result else failure("unstable_target", "Target did not settle. No input was dispatched.")
                delay(70)
            }
        }
        var result = prepare(operation, target)
        if (!result.ok()) return result
        val selector = result["target"]?.jsonPrimitive?.contentOrNull ?: target
        if (result.flag("prepared") && operation != "key") {
            var from = AgentBrowserNativeInput.point(result); var to = from
            if (operation == "drag") {
                val end = prepare("drag", requireNotNull(options.endTarget))
                if (!end.ok()) return end
                to = AgentBrowserNativeInput.point(end)
                // Destination discovery may scroll; do not reuse start coordinates from before it.
                val fresh = run("verify_point", selector)
                if (!fresh.ok() || fresh["point"] != result["point"])
                    return failure("drag_viewport_changed", "Keep both drag targets visible before retrying.")
            }
            if (operation == "swipe") {
                val bounds = result.getValue("bounds").jsonObject
                fun bound(name: String) = bounds.getValue(name).jsonPrimitive.float
                val left = bound("left"); val right = bound("right"); val top = bound("top"); val bottom = bound("bottom")
                val x = (left + right) / 2; val y = (top + bottom) / 2
                val lowX = left + (right - left) * .2f; val highX = left + (right - left) * .8f
                val lowY = top + (bottom - top) * .2f; val highY = top + (bottom - top) * .8f
                from = when (text) { "up" -> AgentBrowserNativeInput.Point(x, highY); "down" -> AgentBrowserNativeInput.Point(x, lowY)
                    "left" -> AgentBrowserNativeInput.Point(highX, y); else -> AgentBrowserNativeInput.Point(lowX, y) }
                to = when (text) { "up" -> AgentBrowserNativeInput.Point(x, lowY); "down" -> AgentBrowserNativeInput.Point(x, highY)
                    "left" -> AgentBrowserNativeInput.Point(lowX, y); else -> AgentBrowserNativeInput.Point(highX, y) }
            }
            val hit = AgentBrowserInteraction.result(evaluate(AgentBrowserInteraction.verifyPoint(selector, from.x, from.y)))
            if (!hit.ok()) return hit
            require(operation !in setOf("swipe", "drag") || from != to) { "Gesture requires distinct start and end positions" }
            AgentBrowserNativeInput.gesture(view, from, to,
                durationMs = if (operation in setOf("drag", "swipe")) options.durationMs else 64,
                mouse = operation == "drag", hover = operation == "hover", dispatch = dispatch, cancel = cancel)
            result = buildJsonObject { put("ok", true); put("action", operation); put("target", selector); put("dispatched", true); put("method", "native-input") }
            if (operation == "check") {
                delay(70)
                val state = run("check_state", selector)
                result = buildJsonObject {
                    val checked = state["checked"]?.jsonPrimitive?.booleanOrNull
                    put("ok", state.ok() && checked == options.checked); put("action", "check"); put("dispatched", true)
                    checked?.let { put("checked", it) }
                    put("note", "Input dispatched once. Read again if state is unconfirmed; do not blindly repeat.")
                }
            }
        }
        if (key != null) {
            dispatch { AgentBrowserNativeInput.press(view, key) }
            result = JsonObject(result + mapOf("dispatched" to JsonPrimitive(true), "prepared" to JsonPrimitive(false)))
        }
        return result
    }

    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.ok() = flag("ok")
    private fun failure(code: String, message: String) = buildJsonObject { put("ok", false); put("code", code); put("message", message) }
}
