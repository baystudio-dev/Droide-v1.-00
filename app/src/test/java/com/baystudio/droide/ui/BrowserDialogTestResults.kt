package com.baystudio.droide.ui

import android.webkit.JsResult
import android.webkit.JsPromptResult
import java.lang.reflect.Proxy
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

internal object BrowserDialogTestResults {
    fun alert(): JsResult = create(JsResult::class.java)
    fun prompt(): JsPromptResult = create(JsPromptResult::class.java)
    fun state(result: JsResult): BrowserDialogResultShadow = Shadow.extract(result)

    private fun <T : JsResult> create(type: Class<T>): T {
        val receiverType = Class.forName("android.webkit.JsResult\$ResultReceiver")
        val receiver = Proxy.newProxyInstance(receiverType.classLoader, arrayOf(receiverType)) { _, _, _ -> null }
        return type.getDeclaredConstructor(receiverType).apply { isAccessible = true }.newInstance(receiver)
    }
}

@Implements(JsResult::class)
open class BrowserDialogResultShadow {
    var confirmations = 0
    var cancellations = 0
    var promptText: String? = null
    var failCompletion = false

    @Implementation fun confirm() {
        confirmations++
        if (failCompletion) throw IllegalStateException("Renderer callback failed")
    }
    @Implementation fun cancel() {
        cancellations++
        if (failCompletion) throw IllegalStateException("Renderer callback failed")
    }
}

@Implements(JsPromptResult::class)
class BrowserDialogPromptResultShadow : BrowserDialogResultShadow() {
    @Implementation fun confirm(value: String) {
        confirmations++
        promptText = value
        if (failCompletion) throw IllegalStateException("Renderer callback failed")
    }
}
