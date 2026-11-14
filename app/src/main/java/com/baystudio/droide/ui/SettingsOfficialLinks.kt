package com.baystudio.droide.ui

/** Fixed destinations published by Droide's website; never populated from project or remote input. */
internal enum class SettingsOfficialLink(val title: String, val url: String) {
    WEBSITE("Official website", "https://droide.rekacipta.biz.id/"),
    SOURCE("Source repository", "https://github.com/baystudio-dev/Droide-v1.-00"),
    TECHNOLOGY("How Droide works", "https://droide.rekacipta.biz.id/technology.html"),
    GITHUB("BayStudio on GitHub", "https://github.com/baystudio-dev"),
    BLUESKY("Bluesky", "https://bsky.app/profile/droideapp.bsky.social"),
    INSTAGRAM("Instagram", "https://instagram.com/droideapp?psln=M2p0YjRpeHpqY3Jh"),
    DISCORD("Discord community", "https://discord.gg/jw3B5mFv6"),
    REPORT_BUG("Report a bug", "https://droide.rekacipta.biz.id/report-bug.html"),
    SUPPORT("Email BayStudio support", "mailto:supportbaystudio@gmail.com"),
    PRIVACY("Open website Privacy Policy", "https://droide.rekacipta.biz.id/privacy.html"),
    TERMS("Open website Terms of Service", "https://droide.rekacipta.biz.id/terms.html");

    val isEmail: Boolean get() = this == SUPPORT
    val targetLabel: String get() = if (isEmail) url.removePrefix("mailto:") else url
    val actionLabel: String get() = if (isEmail) "Compose an email to BayStudio support" else "Open link outside Droide"
    val availabilityNote: String? get() = if (this == INSTAGRAM) {
        "Linked by the official website; destination availability has not been verified."
    } else null

    val unavailableMessage: String get() = if (isEmail) {
        "Could not open an email app. Use the address shown here in your email app."
    } else {
        "Could not open this link. Use the address shown here in a browser."
    }
}

internal object SettingsOfficialLinks {
    val about = listOf(
        SettingsOfficialLink.WEBSITE,
        SettingsOfficialLink.SOURCE,
        SettingsOfficialLink.TECHNOLOGY,
        SettingsOfficialLink.GITHUB,
        SettingsOfficialLink.BLUESKY,
        SettingsOfficialLink.INSTAGRAM,
        SettingsOfficialLink.DISCORD,
    )
    val support = listOf(SettingsOfficialLink.REPORT_BUG, SettingsOfficialLink.SUPPORT)

    const val DEVELOPER_DESCRIPTION = "BayStudio"
    const val EXTERNAL_LINKS_NOTE = "Links open in your browser or another installed app."
    const val SUPPORT_NOTE = "Review reports and emails before sending. Remove secrets and private project data."
    const val PRIVACY_NOTE = "Read BayStudio's published Privacy Policy on the official website. " +
        "This build links to that web document and does not bundle a complete app-specific privacy notice. " +
        "App-specific details about credentials, provider and tool transfers, local history and media retention, " +
        "and disconnect or deletion behavior still need to be reconciled with the published notice. " +
        "Contact BayStudio with privacy questions."
    const val TERMS_NOTE = "Read BayStudio's published Terms of Service on the official website. " +
        "This build links to that web document and does not bundle a complete app-specific terms document. " +
        "This screen does not record acceptance. Component licenses and packaged notices remain available in About & Legal."

    // Exact-target matching is intentional: paths, queries, invite codes and mail recipients are fixed.
    // This is not a general web-link sanitizer or a promise about redirects handled by another app.
    fun isAllowedTarget(url: String): Boolean = SettingsOfficialLink.entries.any { it.url == url }
}
