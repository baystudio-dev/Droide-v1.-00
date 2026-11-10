package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenSourceLicenseCatalogTest {
    @Test fun packagedCatalogOpensEveryLicenseAndNoticeOffline() {
        val context = RuntimeEnvironment.getApplication()
        val entries = OpenSourceLicenseCatalog.load(context).entries
        assertTrue(entries.any { it.id.startsWith("resolved:") })
        assertTrue(entries.any { it.id.startsWith("ubuntu:") })
        assertTrue(entries.any { it.id.startsWith("ubuntu-common:") })
        for (entry in entries) {
            assertTrue(entry.id, OpenSourceLicenseCatalog.readEntry(context, entry).isNotBlank())
        }
    }

    @Test fun commonsLicenseDisplayIncludesItsCopyrightNotice() {
        val context = RuntimeEnvironment.getApplication()
        val entry = OpenSourceLicenseCatalog.load(context).entries.single {
            it.id.startsWith("resolved:commons-codec:commons-codec:")
        }
        val notice = "legal/licenses/Apache-Commons-Codec-NOTICE.txt"
        assertTrue(entry.noticeTextAssets.contains(notice))
        assertTrue(OpenSourceLicenseCatalog.readEntry(context, entry).contains(
            OpenSourceLicenseCatalog.readAsset(context, notice),
        ))
    }
}
