package com.stroexd.hsdecktracker.core.prediction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeteaseOpeningStatsTest {
    @Test
    fun usesOfficialKeepCountsForEveryOfferedEntityIncludingDuplicates() {
        val offers = listOf(
            OpeningOffer(4, "CORE_WC_042"),
            OpeningOffer(20, "CATA_722"),
            OpeningOffer(27, "CORE_UNG_205"),
            OpeningOffer(16, "CATA_722"),
        )
        val rates = projectNeteaseKeepRates(
            """{"status":true,"result":{
            "CORE_WC_042":{"use_cnt":1387,"total_cnt":1398},
            "CATA_722":{"use_cnt":71,"total_cnt":1098},
            "CORE_UNG_205":{"use_cnt":244,"total_cnt":286}
            }}""",
            offers,
        )
        assertEquals(4, rates.size)
        assertEquals(1387 * 100.0 / 1398, rates[4])
        assertEquals(71 * 100.0 / 1098, rates[20])
        assertEquals(rates[20], rates[16])
        assertEquals(244 * 100.0 / 286, rates[27])
    }

    @Test
    fun skipsMissingAndMalformedStatsWhileKeepingARealZeroRate() {
        val offers = listOf(OpeningOffer(1, "A"), OpeningOffer(2, "B"), OpeningOffer(3, "C"))
        assertEquals(mapOf(3 to 0.0), projectNeteaseKeepRates(
            """{"status":true,"result":{
            "A":{"use_cnt":4,"total_cnt":2},
            "B":{"use_cnt":"1","total_cnt":2},
            "C":{"use_cnt":0,"total_cnt":4}
            }}""",
            offers,
        ))
        assertTrue(projectNeteaseKeepRates("""{"status":false,"result":{}}""", offers).isEmpty())
        assertTrue(projectNeteaseKeepRates("unexpected response", offers).isEmpty())
    }
}
