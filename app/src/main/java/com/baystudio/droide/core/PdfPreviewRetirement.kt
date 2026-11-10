package com.baystudio.droide.core

/** Only actual Binder death retires native work; timeout is never evidence of termination. */
internal class PdfPreviewRetirement {
    private var active: Any? = null
    @Synchronized fun started(token: Any) {
        requireStopped()
        active = token
    }
    @Synchronized fun died(token: Any) { if (active === token) active = null }
    @Synchronized fun requireStopped() {
        check(active == null) { "PDF worker shutdown is not confirmed. Wait briefly, then retry." }
    }
}
