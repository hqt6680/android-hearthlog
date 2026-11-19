package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerLogNativeOptionProjectionTest {
    @Test
    fun keepsEndTurnEntitylessAndOnlySerializesAcceptedTargetRows() {
        val actions = PowerLogActionOptions(52, 1, listOf(
            PowerLogActionOption(0, "END_TURN", null, "INVALID", null),
            PowerLogActionOption(1, "POWER", 15, "NONE", null, targets = listOf(
                PowerLogActionTarget(0, 16, "REQ_ENEMY_TARGET", null),
                PowerLogActionTarget(1, 17, "NONE", null),
            )),
            PowerLogActionOption(2, "POWER", 999, "REQ_YOUR_TURN", null),
        ))
        val snapshot = PowerLogSnapshot(
            gameNumber = 1,
            format = GameFormat.WILD,
            turn = 12,
            friendly = PowerLogSide(
                hand = listOf(PowerLogCard("HAND_CARD", entityId = 15)),
                heroEntityId = 66,
                heroCardId = "HERO_02",
                playerId = 2,
                playerEntityId = 3,
            ),
            opponent = PowerLogSide(playerId = 1, playerEntityId = 2),
            result = null,
            localPlayerIdentified = true,
            actionOptions = actions,
        )
        val projection = assertNotNull(projectNativeOptionsForAudit(snapshot, mapOf(66 to mapOf("HEALTH" to 30))))
        val state = projection.comparisonJson.getValue("data").jsonObject.getValue("turns").jsonArray.single()
            .jsonObject.getValue("processes").jsonArray.single().jsonObject.getValue("state").jsonObject
        assertEquals("52", state.getValue("options_id").jsonPrimitive.content)
        val hero = state.getValue("entities").jsonObject.getValue("my_hero").jsonArray.single().jsonObject
        assertEquals(setOf("ENTITY_ID", "_CARD_ID", "HEALTH"), hero.keys)
        assertEquals("66", hero.getValue("ENTITY_ID").jsonPrimitive.content)
        assertFalse(hero.getValue("HEALTH").jsonPrimitive.isString)
        val options = state.getValue("options").jsonArray
        assertEquals(setOf("id", "type", "error"), options[0].jsonObject.keys)
        assertEquals("INVALID", options[0].jsonObject.getValue("error").jsonPrimitive.content)
        val action = options[1].jsonObject
        assertEquals("2", action.getValue("player_id").jsonPrimitive.content)
        assertFalse(action.containsKey("card_id"))
        val target = action.getValue("targets").jsonArray.single().jsonObject
        assertEquals("1", target.getValue("id").jsonPrimitive.content)
        assertEquals("17", target.getValue("entity_id").jsonPrimitive.content)
        assertFalse(options[2].jsonObject.containsKey("player_id"))
        assertTrue("state.options[2].player_id" in projection.unresolvedFields)
        assertTrue("state.entities" in projection.unresolvedFields)
        assertNull(projectNativeOptionsForAudit(snapshot.copy(openingChoice = PowerLogOpeningChoice(2, emptyList()))))
    }
}
