package com.stroexd.hsdecktracker.core.prediction

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.powerlog.PowerLogOpeningCard
import com.stroexd.hsdecktracker.core.powerlog.PowerLogOpeningChoice
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSide
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredictStateLogReaderTest {
    @Test
    fun keepsTheObservedStateBeforeTheSelectedAction() {
        val log = opening + firstDecision + laterDecision + logLine("DebugPrintPower", "RESET_GAME")
        val first = assertNotNull(PredictStateLogReader.read(log.reader(), rawTurn = 12, optionsId = 52))
        assertEquals(GameFormat.WILD, first.format)
        assertEquals(12, first.rawTurn)
        assertEquals(52, first.optionsId)
        assertEquals(2, first.optionCount)
        assertTrue(first.endedAtLoggedAction)
        val hero = first.comparisonJson.getValue("data").jsonObject.getValue("turns").jsonArray.single().jsonObject
            .getValue("processes").jsonArray.single().jsonObject.getValue("state").jsonObject
            .getValue("entities").jsonObject.getValue("my_hero").jsonArray.single().jsonObject
        assertEquals(1, hero.getValue("DAMAGE").jsonPrimitive.int)
        assertTrue("state.deck_card_counter" in first.unresolvedFields)
        assertEquals(53, PredictStateLogReader.read(log.reader())?.optionsId)
        assertNull(PredictStateLogReader.read(log.reader(), rawTurn = 12, optionsId = 53))
    }

    @Test
    fun flagsAnUnsubmittedPacketAsAnObservedPrefix() {
        val pending = firstDecision.lineSequence().filterNot { "GameState.SendOption" in it }.joinToString("\n")
        val preview = assertNotNull(PredictStateLogReader.read((opening + pending).reader()))
        assertEquals(52, preview.optionsId)
        assertFalse(preview.endedAtLoggedAction)
        assertNull(PredictStateLogReader.read((opening.replace("GT_RANKED", "GT_ARENA") + pending).reader()))
    }

    @Test
    fun usesTheVerifiedOpeningOwnerAndRejectsUnknownOpeningEntities() {
        val cards = (1..3).map { PowerLogOpeningCard("CARD_$it", it, entityId = 10 + it) }
        val choice = PowerLogOpeningChoice(2, cards, playerId = 2, sourceEntityId = 1)
        val snapshot = PowerLogSnapshot(1, GameFormat.WILD, 0, PowerLogSide(), PowerLogSide(), null,
            localPlayerIdentified = false, openingChoice = choice)
        assertEquals(PowerLogPredictionAnchor(PowerLogPredictionKind.MULLIGAN, 0, 2),
            snapshot.activePredictionAnchor(500))
        assertNull(snapshot.copy(openingChoice = choice.copy(playerId = null)).activePredictionAnchor(500))
        assertNull(snapshot.copy(openingChoice = choice.copy(cards = cards.map { it.copy(entityId = null) }))
            .activePredictionAnchor(500))
    }

    @Test
    fun replaysAnOpponentPositionWithNoActionPacket() {
        val log = opening + firstDecision + logLine("DebugPrintPower", "TAG_CHANGE Entity=GameEntity tag=TURN value=13") +
            logLine("DebugPrintPower", "TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=0") +
            logLine("DebugPrintPower", "TAG_CHANGE Entity=3 tag=CURRENT_PLAYER value=1")
        val position = assertNotNull(PredictStateLogReader.readActive(log.reader(), PowerLogPredictionKind.OPPONENT))
        assertEquals(13, position.rawTurn)
        assertEquals(position.lastLineNumber, position.optionsId)
        assertEquals(1, position.localPlayerId)
        assertEquals(0, position.optionCount)
        assertNull(PredictStateLogReader.readActive(log.reader()))
    }

    @Test
    fun preservesDiscoverIdentityAndDropsTheChoiceAfterSelection() {
        val choiceLog = opening + firstDecision +
            logLine("DebugPrintEntityChoices", "id=6 Player=Local Player TaskList=7 ChoiceType=GENERAL CountMin=1 CountMax=1") +
            logLine("DebugPrintEntityChoices", "Source=10") + logLine("DebugPrintEntityChoices", "Entities[0]=22")
        val choice = assertNotNull(PredictStateLogReader.readActive(choiceLog.reader(), PowerLogPredictionKind.CHOICE))
        val state = choice.comparisonJson.getValue("data").jsonObject.getValue("turns").jsonArray.single().jsonObject
            .getValue("processes").jsonArray.single().jsonObject.getValue("state").jsonObject.getValue("choice").jsonObject
        assertEquals(6, choice.optionsId)
        assertEquals(10, state.getValue("sourceEntityId").jsonPrimitive.int)
        assertEquals(22, state.getValue("options").jsonArray.single().jsonObject.getValue("entityId").jsonPrimitive.int)
        val submitted = choiceLog + logLine("SendChoices", "id=6 ChoiceType=GENERAL")
        assertNull(PredictStateLogReader.readActive(submitted.reader(), PowerLogPredictionKind.CHOICE))
    }

    private val opening = buildString {
        append(logLine("DebugPrintPower", "CREATE_GAME"))
        append(logLine("DebugPrintPower", "GameEntity EntityID=1"))
        append(logLine("DebugPrintPower", "Player EntityID=2 PlayerID=1 GameAccountId=[hi=123 lo=123]"))
        append(logLine("DebugPrintPower", "Player EntityID=3 PlayerID=2 GameAccountId=[hi=456 lo=456]"))
        append(logLine("DebugPrintGame", "GameType=GT_RANKED"))
        append(logLine("DebugPrintGame", "FormatType=FT_WILD"))
        append(logLine("DebugPrintGame", "PlayerID=1, PlayerName=Local Player"))
        append(logLine("DebugPrintEntityChoices", "id=2 Player=Local Player TaskList=7 ChoiceType=MULLIGAN CountMin=0 CountMax=3"))
        append(logLine("SendChoices", "id=2 ChoiceType=MULLIGAN"))
        append(logLine("DebugPrintPower", "TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION"))
        append(logLine("DebugPrintPower", "TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1"))
        append(logLine("DebugPrintPower", "FULL_ENTITY - Creating ID=10 CardID=HERO_02"))
        append(logLine("DebugPrintPower", "tag=ZONE value=PLAY"))
        append(logLine("DebugPrintPower", "tag=CONTROLLER value=1"))
        append(logLine("DebugPrintPower", "tag=CARDTYPE value=HERO"))
        append(logLine("DebugPrintPower", "tag=HEALTH value=30"))
    }

    private val firstDecision = decision(turn = 12, packet = 52, damage = 1)
    private val laterDecision = decision(turn = 14, packet = 53, damage = 7)

    private fun decision(turn: Int, packet: Int, damage: Int) = buildString {
        append(logLine("DebugPrintPower", "TAG_CHANGE Entity=GameEntity tag=TURN value=$turn"))
        append(logLine("DebugPrintPower", "TAG_CHANGE Entity=10 tag=DAMAGE value=$damage"))
        append(logLine("DebugPrintOptions", "id=$packet"))
        append(logLine("DebugPrintOptions", "option 0 type=END_TURN mainEntity= error=INVALID errorParam="))
        append(logLine("DebugPrintOptions", "option 1 type=POWER mainEntity=10 error=NONE errorParam="))
        append(logLine("SendOption", "selectedOption=0 selectedSubOption=-1 selectedTarget=0 selectedPosition=0"))
    }

    private fun logLine(stream: String, message: String) = "D 01:52:04.3456789 GameState.$stream() - $message\n"
}
