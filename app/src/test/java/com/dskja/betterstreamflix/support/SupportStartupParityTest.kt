package com.dskja.betterstreamflix.support

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupportStartupParityTest {

    @Test
    fun tvStartupOptOutUsesTheSameStringAndIsReachable() {
        val tv = readLayout("dialog_support_startup_tv.xml")
        val mobile = readLayout("dialog_support_startup_mobile.xml")
        assertTrue(tv.contains("@string/support_startup_never_again"))
        assertTrue(mobile.contains("@string/support_startup_never_again"))
        assertTrue(tv.contains("cb_support_startup_never"))
        assertTrue(tv.contains("<ScrollView"))
        assertTrue(tv.contains("nextFocusDown=\"@id/cb_support_startup_never\""))
    }

    @Test
    fun tvHomeBannerKeepsTheCardAndAddsTheSameOptOut() {
        val banner = readLayout("item_support_banner_tv.xml")
        assertTrue(banner.contains("@string/support_banner_title"))
        assertTrue(banner.contains("btn_support_banner_cta"))
        assertTrue(banner.contains("btn_support_banner_dismiss"))
        assertTrue(banner.contains("btn_support_banner_never"))
        assertTrue(banner.contains("@string/support_startup_never_again"))
        assertTrue(banner.contains("descendantFocusability=\"afterDescendants\""))
    }

    private fun readLayout(name: String): String {
        val candidates = listOf(
            File("app/src/main/res/layout/$name"),
            File("../app/src/main/res/layout/$name"),
            File("../../app/src/main/res/layout/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate $name")
    }
}
