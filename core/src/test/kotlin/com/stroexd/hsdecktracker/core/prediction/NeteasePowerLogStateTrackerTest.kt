package com.stroexd.hsdecktracker.core.prediction

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

class NeteasePowerLogStateTrackerTest {
    @Test
    fun `tracks entities, beast race normalization, attachments, deck counters, played cards, and graveyard metadata`() {
        val tracker = NeteasePowerLogStateTracker()
        val log = buildList {
            // Prior game should be completely reset on second CREATE_GAME
            add("GameState.DebugPrintPower() - CREATE_GAME")
            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=99 CardID=STALE_CARD")

            add("GameState.DebugPrintPower() - CREATE_GAME")
            add("GameState.DebugPrintPower() -     GameEntity EntityID=1")
            add("GameState.DebugPrintPower() -         tag=CARDTYPE value=GAME")
            add("GameState.DebugPrintPower() -         tag=TURN value=5")
            add("GameState.DebugPrintGame() - BuildNumber=255790")
            add("GameState.DebugPrintGame() - GameType=GT_RANKED")
            add("GameState.DebugPrintGame() - FormatType=FT_WILD")
            add("GameState.DebugPrintGame() - ScenarioID=2")
            add("GameState.DebugPrintPower() -     Player EntityID=2 PlayerID=1 GameAccountId=[hi=100 lo=200]")
            add("GameState.DebugPrintPower() -     Player EntityID=3 PlayerID=2 GameAccountId=[hi=300 lo=400]")
            add("GameState.DebugPrintGame() - PlayerID=1, PlayerName=Alice#1234")
            add("GameState.DebugPrintGame() - PlayerID=2, PlayerName=Bob#5678")

            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=10 CardID=HERO_01")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO")
            add("GameState.DebugPrintPower() -     tag=ZONE value=PLAY")

            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=11 CardID=HERO_02")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=2")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO")
            add("GameState.DebugPrintPower() -     tag=ZONE value=PLAY")

            // Deck cards (two for local player: one stays in deck, one drawn then played; one created into deck)
            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=20 CardID=DECK_STAY")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=MINION")
            add("GameState.DebugPrintPower() -     tag=ZONE value=DECK")

            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=21 CardID=")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=MINION")
            add("GameState.DebugPrintPower() -     tag=ZONE value=DECK")
            add("GameState.DebugPrintPower() -     tag=CARDRACE value=BEAST")
            add("GameState.DebugPrintPower() -     tag=TAG_EXTRA value=extra-metadata")

            // Revealed drawn and moved to hand -> popped from deck
            add("GameState.DebugPrintPower() - SHOW_ENTITY - Updating Entity=[id=21] CardID=DRAWN_BEAST")
            add("GameState.DebugPrintPower() -     tag=ZONE value=HAND")

            // Play block while card moves hand -> play -> graveyard
            add("GameState.DebugPrintPower() - BLOCK_START BlockType=PLAY Entity=[id=21] EffectCardId= EffectIndex=0 Target=0 SubOption=-1")
            add("GameState.DebugPrintPower() - TAG_CHANGE Entity=21 tag=ZONE value=PLAY")
            add("GameState.DebugPrintPower() - BLOCK_END")

            // Enchantment attached to entity 10
            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=30 CardID=ENCH_01")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=ENCHANTMENT")
            add("GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
            add("GameState.DebugPrintPower() -     tag=ATTACHED value=10")

            // Minion moves to graveyard on turn 5 -> round turn = (5+1)/2 = 3
            add("GameState.DebugPrintPower() - TAG_CHANGE Entity=21 tag=ZONE value=GRAVEYARD")

            // Opponent secret played unrevealed, later revealed via SHOW_ENTITY
            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=40 CardID=")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=2")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL")
            add("GameState.DebugPrintPower() -     tag=ZONE value=HAND")
            add("GameState.DebugPrintPower() - BLOCK_START BlockType=PLAY Entity=[id=40] EffectCardId= EffectIndex=0 Target=0 SubOption=-1")
            add("GameState.DebugPrintPower() - TAG_CHANGE Entity=40 tag=ZONE value=SECRET")
            add("GameState.DebugPrintPower() - BLOCK_END")
            add("GameState.DebugPrintPower() - SHOW_ENTITY - Updating Entity=40 CardID=OPP_SECRET_01")

            // Shuffled new card into local deck (SETASIDE -> DECK)
            add("GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=50 CardID=BOMB_01")
            add("GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            add("GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL")
            add("GameState.DebugPrintPower() -     tag=ZONE value=SETASIDE")
            add("GameState.DebugPrintPower() - TAG_CHANGE Entity=50 tag=ZONE value=DECK")

            // PowerTaskList duplicate should be ignored
            add("PowerTaskList.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=999")
        }
        log.forEach(tracker::processLine)

        val snapshot = tracker.buildSnapshot(localPlayerId = 1)
        val entities = snapshot.entities
        assertNull(entities["99"])
        val game = entities.getValue("game").jsonArray.single().jsonObject
        assertEquals(5, game.getValue("TURN").jsonPrimitive.int)
        assertEquals(255790, game.getValue("BUILD_NUMBER").jsonPrimitive.int)
        assertEquals("GT_RANKED", game.getValue("GAME_TYPE").jsonPrimitive.content)
        assertEquals("FT_WILD", game.getValue("FormatType").jsonPrimitive.content)

        val myHero = entities.getValue("my_hero").jsonArray.single().jsonObject
        assertEquals(listOf(30), myHero.getValue("_ATTACHMENTS").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(1, entities.getValue("attachments").jsonArray.size)

        // FilterDeck keeps ONLY ENTITY_ID and _CARD_ID
        val myDeck = entities.getValue("my_deck").jsonArray.map { it.jsonObject }
        assertEquals(2, myDeck.size)
        assertTrue(myDeck.all { setOf("ENTITY_ID", "_CARD_ID").containsAll(it.keys) })

        // Graveyard has CARDRACE=BEAST -> PET and _ENTER_ZONE_IDX / _ENTER_ZONE_TURN
        val gy = entities.getValue("my_graveyard").jsonArray.single().jsonObject
        assertEquals("PET", gy.getValue("CARDRACE").jsonPrimitive.content)
        assertEquals(1, gy.getValue("_ENTER_ZONE_IDX").jsonPrimitive.int)
        assertEquals(3, gy.getValue("_ENTER_ZONE_TURN").jsonPrimitive.int)

        // Deck counter: current my_deck = 2 (ID 20 + ID 50), pop = {DRAWN_BEAST: 1} (or "" depending on when cardId was set:
        // note SHOW_ENTITY happened right before tag=ZONE value=HAND -> cardId was DRAWN_BEAST!), push = {BOMB_01: 1},
        // so initial = 2 + 1 - 1 = 2!
        val myCounter = snapshot.deckCardCounter.getValue("my_deck").jsonObject
        assertEquals(2, myCounter.getValue("push").jsonObject.getValue("").jsonPrimitive.int)
        assertEquals(1, myCounter.getValue("push").jsonObject.getValue("BOMB_01").jsonPrimitive.int)
        assertEquals(1, myCounter.getValue("pop").jsonObject.getValue("DRAWN_BEAST").jsonPrimitive.int)

        // Played cards: local has 21 (DRAWN_BEAST, turn 3), opp has backfilled 40 (OPP_SECRET_01, turn 3)
        val myPlayed = snapshot.playedCards.getValue("my").jsonArray.single().jsonObject
        assertEquals("DRAWN_BEAST", myPlayed.getValue("_CARD_ID").jsonPrimitive.content)
        assertEquals(3, myPlayed.getValue("_PLAYED_TURN").jsonPrimitive.int)
        val oppPlayed = snapshot.playedCards.getValue("opp").jsonArray.single().jsonObject
        assertEquals("OPP_SECRET_01", oppPlayed.getValue("_CARD_ID").jsonPrimitive.content)
    }

    @Test
    fun `keeps concurrent mulligan choices separate by choice id and puts GENERAL choices in others`() {
        val tracker = NeteasePowerLogStateTracker()
        val lines = listOf(
            "GameState.DebugPrintPower() - CREATE_GAME",
            "GameState.DebugPrintPower() - GameEntity EntityID=1",
            "GameState.DebugPrintPower() - Player EntityID=2 PlayerID=1 GameAccountId=[]",
            "GameState.DebugPrintPower() - Player EntityID=3 PlayerID=2 GameAccountId=[]",
            "GameState.DebugPrintGame() - PlayerID=1, PlayerName=Alice#1234",
            "GameState.DebugPrintGame() - PlayerID=2, PlayerName=Bob#5678",
            "GameState.DebugPrintEntityChoices() - id=1 Player=Alice#1234 TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=3",
            "GameState.DebugPrintEntityChoices() -   Source=GameEntity",
            "GameState.DebugPrintEntityChoices() -   Entities[0]=[id=10 player=1]",
            "GameState.DebugPrintEntityChoices() -   Entities[1]=[id=11 player=1]",
            "GameState.DebugPrintEntityChoices() - id=2 Player=Bob#5678 TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=4",
            "GameState.DebugPrintEntityChoices() -   Source=GameEntity",
            "GameState.DebugPrintEntityChoices() -   Entities[0]=[id=20 player=2]",
            "GameState.DebugPrintEntityChoices() -   Entities[1]=[id=21 player=2]",
        )
        lines.forEach(tracker::processLine)

        val snapshotForPlayer1 = tracker.buildSnapshot(localPlayerId = 1, preferredChoiceId = 1)
        assertNotNull(snapshotForPlayer1.activeChoice)
        assertEquals(1, snapshotForPlayer1.activeChoice?.id)
        assertEquals(listOf(10, 11), snapshotForPlayer1.activeChoice?.entityIds)
        assertFalse("others" in snapshotForPlayer1.entities)

        // Now close mulligans and open a GENERAL choice
        listOf(
            "GameState.SendChoices() - id=1 ChoiceType=MULLIGAN",
            "GameState.DebugPrintEntitiesChosen() - id=2 Player=Bob#5678 EntitiesCount=0",
            "GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION",
            "GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=70 CardID=DISC_OPT_1",
            "GameState.DebugPrintPower() -     tag=CONTROLLER value=1",
            "GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL",
            "GameState.DebugPrintPower() -     tag=ZONE value=SETASIDE",
            "GameState.DebugPrintEntityChoices() - id=5 Player=Alice#1234 TaskList=9 ChoiceType=GENERAL CountMin=1 CountMax=1",
            "GameState.DebugPrintEntityChoices() -   Source=10",
            "GameState.DebugPrintEntityChoices() -   Entities[0]=[id=70 player=1]",
        ).forEach(tracker::processLine)

        val discoverSnapshot = tracker.buildSnapshot(localPlayerId = 1, preferredChoiceId = 5)
        assertEquals("GENERAL", discoverSnapshot.activeChoice?.choiceType)
        val others = discoverSnapshot.entities.getValue("others").jsonArray
        assertEquals(70, others.single().jsonObject.getValue("ENTITY_ID").jsonPrimitive.int)
    }

    @Test
    fun `readLocalBattleTag resolves player metadata and rejects conflicting identities`() {
        val validLog = listOf(
            "D 01:00:00 GameState.DebugPrintPower() - CREATE_GAME",
            "D 01:00:00 GameState.DebugPrintGame() - PlayerID=1, PlayerName=Opponent#9999",
            "D 01:00:00 GameState.DebugPrintGame() - PlayerID=2, PlayerName=LocalUser#1234",
            "D 01:00:01 GameState.DebugPrintEntityChoices() - id=2 Player=LocalUser#1234 TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=4",
            "D 01:00:02 GameState.SendChoices() - id=2 ChoiceType=MULLIGAN",
        )
        assertEquals(
            "LocalUser#1234",
            NeteasePowerLogStateTracker.readLocalBattleTag(validLog.asSequence(), validLog.size, localPlayerId = 2, mulliganChoiceId = 2),
        )

        val conflictLog = listOf(
            "D 01:00:00 GameState.DebugPrintPower() - CREATE_GAME",
            "D 01:00:00 GameState.DebugPrintGame() - PlayerID=1, PlayerName=Alice#1111",
            "D 01:00:01 GameState.DebugPrintEntityChoices() - id=2 Player=Bob#2222 TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=4",
            "D 01:00:02 GameState.SendChoices() - id=2 ChoiceType=MULLIGAN",
        )
        assertNull(
            NeteasePowerLogStateTracker.readLocalBattleTag(conflictLog.asSequence(), conflictLog.size, localPlayerId = 1, mulliganChoiceId = 2),
        )
    }
}
