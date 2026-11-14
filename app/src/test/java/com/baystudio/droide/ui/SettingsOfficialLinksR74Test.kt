package com.baystudio.droide.ui

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM catalog/search checks, not evidence of Android intents, rendering or live destinations. */
class SettingsOfficialLinksR74Test {
    private val expected = linkedMapOf(
        SettingsOfficialLink.WEBSITE to "https://droide.rekacipta.biz.id/",
        SettingsOfficialLink.SOURCE to "https://github.com/baystudio-dev/Droide-v1.-00",
        SettingsOfficialLink.TECHNOLOGY to "https://droide.rekacipta.biz.id/technology.html",
        SettingsOfficialLink.GITHUB to "https://github.com/baystudio-dev",
        SettingsOfficialLink.BLUESKY to "https://bsky.app/profile/droideapp.bsky.social",
        SettingsOfficialLink.INSTAGRAM to "https://instagram.com/droideapp?psln=M2p0YjRpeHpqY3Jh",
        SettingsOfficialLink.DISCORD to "https://discord.gg/jw3B5mFv6",
        SettingsOfficialLink.REPORT_BUG to "https://droide.rekacipta.biz.id/report-bug.html",
        SettingsOfficialLink.SUPPORT to "mailto:supportbaystudio@gmail.com",
        SettingsOfficialLink.PRIVACY to "https://droide.rekacipta.biz.id/privacy.html",
        SettingsOfficialLink.TERMS to "https://droide.rekacipta.biz.id/terms.html",
    )

    @Test fun catalogMatchesEveryApprovedTargetExactly() {
        assertEquals(expected, SettingsOfficialLink.entries.associateWith { it.url })
        assertEquals(11, SettingsOfficialLink.entries.map { it.url }.distinct().size)
    }

    @Test fun onlyExactHttpsAndSingleMailtoTargetsAreRepresentable() {
        SettingsOfficialLink.entries.forEach { link ->
            val uri = URI(link.url)
            assertEquals(if (link.isEmail) "mailto" else "https", uri.scheme)
            assertNull(uri.userInfo)
            assertNull(uri.fragment)
            assertEquals(-1, uri.port)
            if (!link.isEmail) assertTrue(uri.host in setOf("droide.rekacipta.biz.id", "github.com", "bsky.app", "instagram.com", "discord.gg"))
            assertTrue(SettingsOfficialLinks.isAllowedTarget(link.url))
        }
        assertEquals(listOf(SettingsOfficialLink.SUPPORT), SettingsOfficialLink.entries.filter { it.isEmail })
    }

    @Test fun arbitraryOrDangerousTargetsNeverPassTheExactAllowlist() {
        listOf("", "javascript:alert(1)", "intent://droide", "file:///data/private", "content://droide/private",
            "http://droide.rekacipta.biz.id/", "https://example.com/", "mailto:attacker@example.com",
            "https://droide.rekacipta.biz.id.evil.test/privacy.html", "https://droide.rekacipta.biz.id@evil.test/",
            "https://evil.test@droide.rekacipta.biz.id/", "https://droide.rekacipta.biz.id:443/",
            "https://droide.rekacipta.biz.id/privacy.html#changed", "https://droide.rekacipta.biz.id//privacy.html",
            "https://droide.rekacipta.biz.id/../privacy.html", "https://droide.rekacipta.biz.id/%70rivacy.html",
            " HTTPS://droide.rekacipta.biz.id/", "https://droide.rekacipta.biz.id/\n",
            "mailto:supportbaystudio@gmail.com?bcc=attacker@example.com", "mailto:supportbaystudio@gmail.com,other@example.com",
        ).forEach { assertFalse(it, SettingsOfficialLinks.isAllowedTarget(it)) }
    }

    @Test fun arbitraryQueryParametersAndSuffixesCannotChangeApprovedTargets() {
        expected.values.forEach { url ->
            listOf("?redirect=https://evil.test", "&token=private", "/unapproved", "#fragment", "\u0000").forEach { suffix ->
                assertFalse(url + suffix, SettingsOfficialLinks.isAllowedTarget(url + suffix))
            }
        }
    }

    @Test fun unusualRepositorySpellingAndInstagramQueryRemainUnchanged() {
        assertEquals("/baystudio-dev/Droide-v1.-00", URI(SettingsOfficialLink.SOURCE.url).path)
        assertEquals("psln=M2p0YjRpeHpqY3Jh", URI(SettingsOfficialLink.INSTAGRAM.url).rawQuery)
        assertEquals(listOf(SettingsOfficialLink.INSTAGRAM), SettingsOfficialLink.entries.filter { URI(it.url).rawQuery != null })
    }

    @Test fun inviteRemainsPublishedOriginalInsteadOfObservedRedirect() {
        assertEquals("https://discord.gg/jw3B5mFv6", SettingsOfficialLink.DISCORD.url)
        assertFalse(SettingsOfficialLinks.isAllowedTarget("https://discord.com/invite/jw3B5mFv6"))
    }

    @Test fun emailHasOneFixedRecipientAndNoContentOrAttachments() {
        val uri = URI(SettingsOfficialLink.SUPPORT.url)
        assertEquals("supportbaystudio@gmail.com", uri.rawSchemeSpecificPart)
        assertEquals("supportbaystudio@gmail.com", SettingsOfficialLink.SUPPORT.targetLabel)
        assertNull(uri.rawQuery)
        assertNull(uri.rawFragment)
    }

    @Test fun officialAndSupportGroupsCoverAboutTargetsOnceAndDoNotBypassLegalRoutes() {
        assertEquals(listOf(SettingsOfficialLink.WEBSITE, SettingsOfficialLink.SOURCE, SettingsOfficialLink.TECHNOLOGY,
            SettingsOfficialLink.GITHUB, SettingsOfficialLink.BLUESKY, SettingsOfficialLink.INSTAGRAM, SettingsOfficialLink.DISCORD), SettingsOfficialLinks.about)
        assertEquals(listOf(SettingsOfficialLink.REPORT_BUG, SettingsOfficialLink.SUPPORT), SettingsOfficialLinks.support)
        val about = SettingsOfficialLinks.about + SettingsOfficialLinks.support
        assertEquals(9, about.distinct().size)
        assertFalse(SettingsOfficialLink.PRIVACY in about)
        assertFalse(SettingsOfficialLink.TERMS in about)
    }

    @Test fun everyActionExposesItsActualTargetAndClearLaunchSemantics() {
        SettingsOfficialLink.entries.forEach { link ->
            assertTrue(link.title.isNotBlank())
            assertTrue(link.actionLabel.isNotBlank())
            assertEquals(if (link.isEmail) "supportbaystudio@gmail.com" else link.url, link.targetLabel)
            assertTrue(if (link.isEmail) link.actionLabel.contains("Compose") else link.actionLabel.contains("outside Droide"))
        }
    }

    @Test fun instagramIsTheOnlyUnverifiedDestinationDisclosure() {
        assertEquals(listOf(SettingsOfficialLink.INSTAGRAM), SettingsOfficialLink.entries.filter { it.availabilityNote != null })
        assertTrue(SettingsOfficialLink.INSTAGRAM.availabilityNote!!.contains("has not been verified"))
    }

    @Test fun labelsDoNotAssertOrganizationLicenseOrDownloadAvailability() {
        assertEquals("BayStudio on GitHub", SettingsOfficialLink.GITHUB.title)
        assertEquals("Source repository", SettingsOfficialLink.SOURCE.title)
        // Inspect destination labels, not the verb "Open" in an accessibility action.
        val copy = SettingsOfficialLink.entries.joinToString { it.title }.lowercase()
        listOf("organization", "registered", "open source", "download", "release", "mit").forEach { assertFalse(it, copy.contains(it)) }
    }

    @Test fun noGuessedDownloadDocsOrRepositoryPathsAreAccepted() {
        listOf("https://github.com/baystudio-dev/Droide-v1.-00/releases", "https://droide.rekacipta.biz.id/download",
            "https://droide.rekacipta.biz.id/docs", "https://github.com/baystudio-dev/Droide-v1.-00/issues").forEach {
            assertFalse(it, SettingsOfficialLinks.isAllowedTarget(it))
        }
    }

    @Test fun aboutIdentityUsesConciseDeveloperName() {
        assertEquals("BayStudio", SettingsOfficialLinks.DEVELOPER_DESCRIPTION)
        assertTrue(SettingsDiscovery.matches("ABOUT", "developer BayStudio"))
        assertFalse(SettingsOfficialLinks.DEVELOPER_DESCRIPTION.contains("company", ignoreCase = true))
        assertFalse(SettingsOfficialLinks.DEVELOPER_DESCRIPTION.contains("registered", ignoreCase = true))
    }

    @Test fun legalCopyNamesThePublishedDocumentAndIncompleteAppCoverage() {
        assertTrue(SettingsOfficialLinks.PRIVACY_NOTE.contains("published Privacy Policy"))
        assertTrue(SettingsOfficialLinks.PRIVACY_NOTE.contains("does not bundle a complete app-specific privacy notice"))
        assertTrue(SettingsOfficialLinks.PRIVACY_NOTE.contains("credentials, provider and tool transfers, local history and media retention"))
        assertTrue(SettingsOfficialLinks.PRIVACY_NOTE.contains("disconnect or deletion"))
        assertTrue(SettingsOfficialLinks.TERMS_NOTE.contains("published Terms of Service"))
        assertTrue(SettingsOfficialLinks.TERMS_NOTE.contains("does not bundle a complete app-specific terms document"))
        assertTrue(SettingsOfficialLinks.TERMS_NOTE.contains("does not record acceptance"))
        assertTrue(SettingsOfficialLinks.TERMS_NOTE.contains("Component licenses and packaged notices remain available"))
    }

    @Test fun legalCopyDoesNotInventDatesRetentionPromisesOrAgreementButtons() {
        val copy = (SettingsOfficialLinks.PRIVACY_NOTE + SettingsOfficialLinks.TERMS_NOTE).lowercase()
        listOf("2026", "effective", "12 months", "48 hours", "i agree", "by using", "governed by", "registered", "ad-free", "always free").forEach {
            assertFalse(it, copy.contains(it))
        }
    }

    @Test fun failuresHaveActionablePublicAddressFallbackWithoutExceptionText() {
        SettingsOfficialLink.entries.forEach { assertTrue(it.unavailableMessage.contains("address shown here")) }
        assertTrue(SettingsOfficialLink.SUPPORT.unavailableMessage.contains("email app"))
        assertTrue(SettingsOfficialLink.WEBSITE.unavailableMessage.contains("browser"))
    }

    @Test fun supportGuidanceWarnsBeforeTheUserSendsPrivateContent() {
        assertTrue(SettingsOfficialLinks.SUPPORT_NOTE.contains("Review reports and emails before sending"))
        assertTrue(SettingsOfficialLinks.SUPPORT_NOTE.contains("Remove secrets and private project data"))
        assertTrue(SettingsOfficialLinks.EXTERNAL_LINKS_NOTE.contains("browser or another installed app"))
    }

    @Test fun aboutSearchIncludesAllNewLinkAndContactLabels() {
        (SettingsOfficialLinks.about + SettingsOfficialLinks.support).forEach { assertTrue(it.title, SettingsDiscovery.matches("ABOUT", it.title)) }
        listOf("official website", "supportbaystudio@gmail.com", "GitHub", "Discord", "Privacy", "Terms of Service", "app-specific notice").forEach {
            assertTrue(it, SettingsDiscovery.matches("ABOUT", it))
        }
    }

    @Test fun searchDoesNotRetainFalseMissingPolicyOrTermsClaims() {
        val copy = SettingsDiscovery.entries.getValue("ABOUT").searchTerms.joinToString(" ")
        assertFalse(copy.contains("policy not bundled yet"))
        assertFalse(copy.contains("Terms of Service not configured"))
        assertFalse(SettingsDiscovery.matches("ABOUT", "download APK"))
        assertFalse(SettingsDiscovery.matches("ABOUT", "accept terms"))
    }
}
