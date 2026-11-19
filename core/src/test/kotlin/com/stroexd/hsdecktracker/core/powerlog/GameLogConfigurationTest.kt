package com.stroexd.hsdecktracker.core.powerlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameLogConfigurationTest {
    @Test
    fun createsBothLogSectionsWhenConfigurationIsEmpty() {
        val enabled = enableConstructedGameLogs("")
        assertEquals(
            "[Power]\nLogLevel=1\nFilePrinting=true\nConsolePrinting=false\nScreenPrinting=false\n\n" +
                "[Decks]\nLogLevel=1\nFilePrinting=true\nConsolePrinting=false\nScreenPrinting=false\n",
            enabled,
        )
        assertEquals(enabled, enableConstructedGameLogs(enabled))
    }

    @Test
    fun preservesUnrelatedLinesAndUpdatesExistingSettingsInPlace() {
        val original = "\uFEFF[Debug]\r\nLogLevel=3\r\nFilePrinting=false\r\n\r\n" +
            "[POWER] ; retain header\r\n  loglevel = 4  ; keep comment\r\nConsolePrinting=true\r\n" +
            "[Decks]\r\nFilePrinting = FALSE # keep comment"
        val enabled = enableConstructedGameLogs(original)
        assertTrue(enabled.startsWith("\uFEFF[Debug]\r\nLogLevel=3\r\nFilePrinting=false\r\n\r\n"))
        assertTrue("  loglevel = 1  ; keep comment\r\nConsolePrinting=true\r\nFilePrinting=true\r\n" in enabled)
        assertTrue("[Decks]\r\nFilePrinting = true # keep comment\r\nLogLevel=1\r\n" in enabled)
        assertEquals(enabled, enableConstructedGameLogs(enabled))
    }

    @Test
    fun leavesAnAlreadyEnabledConfigurationByteForByte() {
        val original = "[Power]\nLogLevel=1\nFilePrinting=TRUE\n[Decks]\nLogLevel=1\nFilePrinting=true"
        assertEquals(original, enableConstructedGameLogs(original))
    }
}
