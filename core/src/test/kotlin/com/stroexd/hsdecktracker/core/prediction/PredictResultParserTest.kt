package com.stroexd.hsdecktracker.core.prediction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredictResultParserTest {
    @Test
    fun `reads win rate and recommendation details from a synthetic r9 response`() {
        val result = PredictResultParser.parse(response(
            """[{"option_id":2,"option_entity_id":54,"target_entity_id":121,"sub_option_id":1,
                 "sub_option_entity_id":80,"position":3,"chosen":[19,21],"magnetic_target_entity_id":122,
                 "using_disguised":1},{"option_id":0,"option_entity_id":0}]""",
            """{"status":200,"winrate":0.2838}""",
        ))

        assertTrue(result.isComplete)
        assertEquals(28.38, result.winRatePercent!!, 1e-9)
        val (play, endTurn) = result.actions
        assertEquals(PredictAction(2, 54, 121, 1, 80, 3, listOf(19, 21), 122, 1), play)
        assertFalse(play.isEndTurn)
        assertTrue(endTurn.isEndTurn)
    }

    @Test
    fun `does not treat an empty action as end turn or a malformed array as complete`() {
        val result = PredictResultParser.parse(response(
            """[{}, {"option_id":1,"position":"bad"}, {"chosen":[7]}]""",
            """{"status":200,"winrate":0}""",
        ))

        assertFalse(result.isComplete)
        assertEquals(0.0, result.winRatePercent)
        assertEquals(listOf("result.rec[0]", "result.rec[1]"), result.missingFields)
        assertEquals(listOf(7), result.actions.single().chosen)
    }

    @Test
    fun `reports unavailable win rate and missing recommendations`() {
        val result = PredictResultParser.parse(response("[]", """{"status":200,"winrate":101}"""))

        assertFalse(result.isComplete)
        assertNull(result.winRatePercent)
        assertTrue(result.actions.isEmpty())
        assertEquals(listOf("result.winrate_data.winrate", "result.rec"), result.missingFields)
    }

    @Test
    fun `reads win rate without recommendations or an explicit response code`() {
        val result = PredictResultParser.parse(
            """{"status":true,"result":{"status":200,"winrate_data":{"status":200,"winrate":1}}}""",
        )

        assertFalse(result.isComplete)
        assertEquals(100.0, result.winRatePercent)
        assertEquals(listOf("result.rec"), result.missingFields)
    }

    @Test
    fun `nonzero response code only suppresses win rate while null is invalid`() {
        val json = response("""[{"option_id":5}]""", """{"status":200,"winrate":0.6}""")
        val result = PredictResultParser.parse(json.replace("\"ret\":0", "\"ret\":1"))

        assertEquals(5, result.actions.single().optionId)
        assertNull(result.winRatePercent)
        assertFalse(result.isComplete)
        assertFailsWith<IllegalArgumentException> {
            PredictResultParser.parse(json.replace("\"ret\":0", "\"ret\":null"))
        }
    }

    @Test
    fun `rejects a failed or malformed response without echoing the input`() {
        listOf("{broken payload", """{"status":false,"ret":9,"msg":"server details"}""").forEach { json ->
            val error = assertFailsWith<IllegalArgumentException> { PredictResultParser.parse(json) }
            assertFalse(error.message.orEmpty().contains("payload"))
            assertFalse(error.message.orEmpty().contains("server details"))
        }
    }

    private fun response(actions: String, winRate: String): String = """
        {"status":true,"ret":0,"result":{"status":200,"rec":$actions,"winrate_data":$winRate}}
        """.trimIndent()
}
