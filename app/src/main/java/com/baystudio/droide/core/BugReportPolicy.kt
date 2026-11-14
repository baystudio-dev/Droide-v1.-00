package com.baystudio.droide.core

/** Text-only native counterpart of the official report-bug.html form. Never adds diagnostics. */
internal data class BugReportDraft(
    val title: String = "",
    val category: BugReportCategory? = null,
    val priority: BugReportPriority = BugReportPriority.NORMAL,
    val description: String = "",
    val steps: String = "",
    val expected: String = "",
    val actual: String = "",
    val version: String = "",
    val android: String = "",
    val device: String = "",
    val email: String = "",
    val privacyAcknowledged: Boolean = false,
)

internal enum class BugReportCategory(val value: String, val label: String) {
    CRASH("crash", "App crash"), EDITOR("editor", "Editor"), AGENT("agent", "AI Agent"),
    TERMINAL("terminal", "Terminal / Linux"), BUILD("build", "Build / Run"),
    EXTENSIONS("extensions", "Extensions"), OTHER("other", "Other"),
}

internal enum class BugReportPriority(val value: String, val label: String) {
    NORMAL("normal", "Normal"), HIGH("high", "High"), BLOCKING("blocking", "Blocks my work"),
}

internal enum class BugReportField {
    TITLE, CATEGORY, DESCRIPTION, STEPS, EXPECTED, ACTUAL, VERSION, ANDROID, DEVICE, EMAIL, PRIVACY,
}

internal object BugReportPolicy {
    const val ORIGIN = "https://droide.rekacipta.biz.id"
    const val FORM_URL = "$ORIGIN/report-bug.html"
    const val CSRF_URL = "$ORIGIN/api/csrf.php"
    const val SUBMIT_URL = "$ORIGIN/api/report-bug.php"
    const val SUPPORT_EMAIL = "supportbaystudio@gmail.com"
    const val MAX_RESPONSE_BYTES = 16 * 1024

    fun validate(draft: BugReportDraft): Map<BugReportField, String> = buildMap {
        fun text(field: BugReportField, value: String, min: Int, max: Int) {
            if (value.length !in min..max || (min > 0 && value.trim().length < min)) {
                put(field, if (min > 0) "Use $min–$max characters." else "Use at most $max characters.")
            } else if (!hasValidSurrogates(value)) {
                put(field, "Remove incomplete Unicode characters.")
            } else if (value.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) {
                put(field, "Remove unsupported control characters.")
            }
        }
        text(BugReportField.TITLE, draft.title, 8, 120)
        if (draft.category == null) put(BugReportField.CATEGORY, "Choose an area.")
        text(BugReportField.DESCRIPTION, draft.description, 30, 4000)
        text(BugReportField.STEPS, draft.steps, 10, 2500)
        text(BugReportField.EXPECTED, draft.expected, 0, 1200)
        text(BugReportField.ACTUAL, draft.actual, 0, 1200)
        text(BugReportField.VERSION, draft.version, 0, 50)
        text(BugReportField.ANDROID, draft.android, 0, 60)
        text(BugReportField.DEVICE, draft.device, 0, 100)
        text(BugReportField.EMAIL, draft.email, 0, 254)
        if (draft.email.isNotEmpty() && !EMAIL.matches(draft.email)) {
            put(BugReportField.EMAIL, "Check the email address.")
        }
        if (!draft.privacyAcknowledged) put(BugReportField.PRIVACY, "Agree to the privacy notice before sending.")
    }

    private fun hasValidSurrogates(value: String): Boolean {
        var at = 0
        while (at < value.length) {
            val char = value[at++]
            if (Character.isHighSurrogate(char)) {
                if (at >= value.length || !Character.isLowSurrogate(value[at++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }

    // HTML email input's common ASCII form, without coercing or silently changing the user's address.
    private val EMAIL = Regex("[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*")

    fun formFields(draft: BugReportDraft): Map<String, String> {
        require(validate(draft).isEmpty()) { "Invalid bug-report fields" }
        return linkedMapOf(
            "title" to draft.title, "category" to requireNotNull(draft.category).value,
            "priority" to draft.priority.value, "description" to draft.description, "steps" to draft.steps,
            "expected" to draft.expected, "actual" to draft.actual, "version" to draft.version,
            "android" to draft.android, "device" to draft.device, "email" to draft.email,
            "website" to "", "privacy_ack" to "yes",
        )
    }
}

/** Only documented codes from the official form. Never display arbitrary server text/HTML. */
internal enum class BugReportError(val code: String, val message: String) {
    NOT_CONFIGURED("not_configured", "Email reporting is being configured. Contact supportbaystudio@gmail.com."),
    MAIL_UNAVAILABLE("mail_unavailable", "Email reporting is temporarily unavailable. Contact supportbaystudio@gmail.com."),
    MAIL_FAILED("mail_failed", "The service could not send this report. Try again later or contact supportbaystudio@gmail.com."),
    RATE_LIMIT("rate_limit", "Too many reports from this connection. Try again in an hour."),
    TOO_FAST("too_fast", "Wait a moment, then tap Send again."),
    INVALID_FIELDS("invalid_fields", "Check the form fields and try again."),
    INVALID_EMAIL("invalid_email", "Check the email address."),
    REQUIRED_FIELDS("required_fields", "Add a title, description and steps to reproduce."),
    INVALID_IMAGE("invalid_image", "The service rejected the screenshot format."),
    TOO_LARGE("too_large", "The service rejected the report size."),
    PRIVACY_ACK("privacy_ack", "Agree to the privacy notice before sending."),
    SESSION_EXPIRED("session_expired", "The secure form session expired. Prepare the form again."),
    FORBIDDEN("forbidden", "The service rejected this request. Prepare the form again."),
    METHOD("method", "The service does not allow this request."),
    UNAVAILABLE("unavailable", "The reporting service is unavailable. Contact supportbaystudio@gmail.com."),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable", "The reporting service is temporarily unavailable. Contact supportbaystudio@gmail.com."),
}

internal enum class BugReportPreparation { READY, FAILED, BUSY, CLOSED }

internal sealed interface BugReportOutcome {
    data class Accepted(val reportId: String? = null) : BugReportOutcome
    data class Invalid(val fields: Map<BugReportField, String>) : BugReportOutcome
    data class Rejected(val error: BugReportError) : BugReportOutcome
    data object PreparationFailed : BugReportOutcome
    data object UnknownSubmission : BugReportOutcome
    data object RetryConfirmationRequired : BugReportOutcome
    data object Busy : BugReportOutcome
    data object Closed : BugReportOutcome
}
