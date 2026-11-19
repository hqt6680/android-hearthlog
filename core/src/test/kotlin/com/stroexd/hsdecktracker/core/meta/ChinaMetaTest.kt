package com.stroexd.hsdecktracker.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChinaMetaTest {
    @Test
    fun readsDeckRowsAndKeepsMissingMetricsUnknown() {
        val row = ChinaMeta.parse(
            ChinaMetaKind.DECKS,
            """var CARD_GROUP_STATISTICS = [
                {"format_type":"FT_STANDARD","title":"控制战","level_type":"all","job":"01",
                "cipher":"AAEC","use_cnt_1d":0,"win_cnt_1d":0,"total_cnt_1d":50,
                "use_cnt_7d":100,"win_cnt_7d":60,"total_cnt_7d":1000,"battle_avg_time_7d":360},
                {"format_type":"FT_ARENA","title":"竞技场","level_type":"all"}
            ];""",
        ).single()
        val week = row.periods.getValue(7)
        assertEquals("控制战", row.title)
        assertEquals("AAEC", row.code)
        assertEquals(0.6, week.winRate)
        assertEquals(0.1, week.pickRate)
        assertEquals(2.0, checkNotNull(week.netWinsPerHour), 1e-9)
        assertNull(row.periods.getValue(1).winRate)
        assertNull(row.periods.getValue(1).netWinsPerHour)
    }

    @Test
    fun parsesClassDataAndRejectsScriptSuffixes() {
        val response = """var HERO_STATISTICS = [
            {"format_type":"FT_WILD","hero":"rogue","level_type":"legend",
            "use_cnt_30d":60,"win_cnt_30d":29,"total_cnt_30d":150,"battle_avg_time_30d":300}
        ]"""
        val row = ChinaMeta.parse(ChinaMetaKind.CLASSES, response).single()
        assertEquals("rogue", row.classId)
        assertEquals("legend", row.rank)
        assertNull(row.code)
        assertFailsWith<IllegalArgumentException> {
            ChinaMeta.parse(ChinaMetaKind.CLASSES, "$response; alert(1)")
        }
        assertFailsWith<IllegalArgumentException> {
            ChinaMeta.parse(ChinaMetaKind.DECKS, response)
        }
    }
}
