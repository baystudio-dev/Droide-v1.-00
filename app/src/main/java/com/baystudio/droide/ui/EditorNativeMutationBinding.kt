package com.baystudio.droide.ui

/** Exact document/view binding. All callbacks run synchronously on the editor owner. */
internal class EditorNativeMutationBinding(
    val owner: Any,
    private val isCurrent: () -> Boolean,
    private val captureSettled: () -> Unit,
    private val replaceAndPublish: (String, String, () -> Unit) -> Unit,
) {
    private var applying = false

    fun requireCaptureAllowed() {
        check(isCurrent()) { "Native editor ownership changed" }
        check(!applying) { "Native editor mutation is still publishing" }
    }

    fun prepare() {
        requireCaptureAllowed()
        captureSettled()
        requireCaptureAllowed()
    }

    fun apply(before: String, after: String, publish: () -> Unit) {
        requireCaptureAllowed()
        applying = true
        var published = false
        var publicationOpen = true
        try {
            replaceAndPublish(before, after) {
                check(publicationOpen) { "Native editor publication callback expired" }
                check(isCurrent()) { "Native editor ownership changed before publication" }
                check(!published) { "Native editor mutation published twice" }
                published = true
                publish()
            }
            check(published) { "Native editor mutation was not published" }
            check(isCurrent()) { "Native editor ownership changed during publication" }
        } finally {
            publicationOpen = false
            applying = false
        }
    }
}
