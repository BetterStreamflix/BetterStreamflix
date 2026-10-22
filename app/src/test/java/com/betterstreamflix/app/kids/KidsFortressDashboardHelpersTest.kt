package com.betterstreamflix.app.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class KidsFortressDashboardHelpersTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun countFilesInFolder_emptyDir_isZero() {
        val dir = tmp.newFolder("dl")
        assertEquals(0, KidsFortressDashboard.countFilesInFolder(dir))
        assertEquals(0L, KidsFortressDashboard.folderBytes(dir))
    }

    @Test
    fun countFilesInFolder_countsNestedFiles() {
        val dir = tmp.newFolder("dl2")
        File(dir, "a.bin").writeBytes(ByteArray(10))
        val nested = File(dir, "sub").also { it.mkdirs() }
        File(nested, "b.bin").writeBytes(ByteArray(5))
        assertEquals(2, KidsFortressDashboard.countFilesInFolder(dir))
        assertEquals(15L, KidsFortressDashboard.folderBytes(dir))
    }

    @Test
    fun formatCardSummary_pinReflectsPerKidOnly() {
        val withPin = KidsFortressPolicy.formatCardSummary(
            maxAge = 10,
            hasPin = true,
            bedtimeEnabled = false,
            bedtimeLocked = false,
            continueCount = 0,
            downloadCount = 0,
            pinLabel = "PIN",
            noPinLabel = "no PIN",
            bedtimeOnLabel = "on",
            bedtimeOffLabel = "off",
            bedtimeLockedLabel = "locked",
            continueLabel = "CW",
            downloadsLabel = "DL",
        )
        assertTrue(withPin.contains("PIN"))
        assertFalse(withPin.contains("no PIN"))
        val noPin = KidsFortressPolicy.formatCardSummary(
            maxAge = 10,
            hasPin = false,
            bedtimeEnabled = false,
            bedtimeLocked = false,
            continueCount = 1,
            downloadCount = 0,
            pinLabel = "PIN",
            noPinLabel = "no PIN",
            bedtimeOnLabel = "on",
            bedtimeOffLabel = "off",
            bedtimeLockedLabel = "locked",
            continueLabel = "CW",
            downloadsLabel = "DL",
        )
        assertTrue(noPin.contains("no PIN"))
    }
}
