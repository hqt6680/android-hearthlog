package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerLogTrackerTest {
    @Test
    fun mulliganSendIdentifiesPlayerWithNoKeptCardsAndRecordsNamedResult() {
        val tracker = constructed()
        tracker.feed("DebugPrintGame", "PlayerID=2, PlayerName=local name")
        tracker.card(20, "EX1_001", "HAND", 1, kind = "MINION")
        tracker.card(21, "CS2_029", "HAND", 2, kind = "SPELL")
        tracker.card(22, "HERO_08a", "PLAY", 2, kind = "HERO")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=3")

        val unidentified = assertNotNull(tracker.snapshot)
        assertFalse(unidentified.localPlayerIdentified)
        assertEquals(1, unidentified.friendly.handCount)
        assertEquals(1, unidentified.opponent.handCount)
        assertTrue(unidentified.friendly.hand.isEmpty())
        assertTrue(unidentified.opponent.hand.isEmpty())

        tracker.feed("DebugPrintEntityChoices", "id=2 Player=UNKNOWN HUMAN PLAYER TaskList=7 ChoiceType=MULLIGAN CountMin=0 CountMax=3")
        tracker.feed("DebugPrintEntityChoices", "Entities[0]=[entityName=Fireball id=21 zone=HAND zonePos=1 cardId=CS2_029 player=2]")
        tracker.feed("SendChoices", "id=2 ChoiceType=MULLIGAN")
        tracker.power("TAG_CHANGE Entity=local name tag=PLAYSTATE value=CONCEDED")
        assertNull(tracker.snapshot?.result)
        tracker.power("TAG_CHANGE Entity=local name tag=PLAYSTATE value=LOST")

        val snapshot = assertNotNull(tracker.snapshot)
        assertTrue(snapshot.localPlayerIdentified)
        assertEquals(GameFormat.WILD, snapshot.format)
        assertEquals(3, snapshot.turn)
        assertEquals(HsClass.MAGE, snapshot.friendly.heroClass)
        assertEquals(listOf("CS2_029"), snapshot.friendly.hand.map { it.cardId })
        assertTrue(snapshot.opponent.hand.isEmpty())
        assertEquals(MatchResult.LOSS, snapshot.result)
        assertFalse(snapshot.toString().contains("local name"))
    }

    @Test
    fun countsDeckAndBoardRegionsKeepsHiddenSecretsAndHiddenDiscardOutOfPublicLists() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "", "DECK", 1)
        tracker.card(11, "EX1_287", "SECRET", 1, kind = "SPELL")
        tracker.card(12, "EX1_277", "HAND", 2, kind = "SPELL")
        tracker.card(13, "EX1_294", "HAND", 2, kind = "SPELL")
        tracker.card(14, "CS2_182", "PLAY", 2, "MINION", 4)

        val opening = assertNotNull(tracker.snapshot)
        assertEquals(1, opening.friendly.deckCount)
        assertNull(opening.opponent.deckCount)
        assertEquals(listOf("EX1_287"), opening.friendly.secrets.map { it.cardId })
        assertEquals(4, opening.opponent.boardAttack)

        tracker.power("TAG_CHANGE Entity=10 tag=ZONE value=HAND")
        tracker.power("TAG_CHANGE Entity=12 tag=ZONE value=GRAVEYARD")
        tracker.power("BLOCK_START BlockType=PLAY Entity=13 EffectCardId= EffectIndex=0 Target=0")
        tracker.power("TAG_CHANGE Entity=13 tag=ZONE value=PLAY")
        assertTrue(tracker.snapshot?.opponent?.played.orEmpty().isEmpty())
        tracker.power("TAG_CHANGE Entity=13 tag=ZONE value=SECRET")
        tracker.power("BLOCK_END")
        tracker.power("TAG_CHANGE Entity=14 tag=ATK value=6")
        assertEquals(6, tracker.snapshot?.opponent?.boardAttack)
        tracker.power("TAG_CHANGE Entity=14 tag=ZONE value=GRAVEYARD")

        val final = assertNotNull(tracker.snapshot)
        assertEquals(0, final.friendly.deckCount)
        assertEquals(1, final.friendly.handCount)
        assertEquals(listOf("CS2_182"), final.opponent.graveyard.map { it.cardId })
        assertEquals(1, final.opponent.secretCount)
        assertTrue(final.opponent.secrets.isEmpty())
        assertTrue(final.opponent.played.isEmpty())
        assertTrue(final.publicPlays.isEmpty())
        assertEquals(0, final.opponent.boardAttack)
        assertFalse(final.toString().contains("EX1_294"))
        assertFalse(final.toString().contains("EX1_277"))
    }

    @Test
    fun auditsOnlyExplicitCurrentBattlefieldTags() {
        val tracker = constructed(captureCombatTags = true)
        tracker.card(10, "HERO_02", "PLAY", 1, "HERO")
        tracker.power("tag=45 value=30")
        tracker.power("TAG_CHANGE Entity=10 tag=DAMAGE value=1")
        tracker.power("TAG_CHANGE Entity=10 tag=44 value=12")
        tracker.card(11, "PRIVATE_CARD", "HAND", 2, "MINION", 5)

        assertEquals(mapOf(10 to mapOf("CONTROLLER" to 1, "HEALTH" to 30, "DAMAGE" to 12)), tracker.observedBattlefieldTags())
        tracker.power("CHANGE_ENTITY - Updating Entity=10 CardID=HERO_03")
        tracker.power("tag=HEALTH value=35")
        assertEquals(mapOf(10 to mapOf("HEALTH" to 35)), tracker.observedBattlefieldTags())
        tracker.reset()
        assertTrue(tracker.observedBattlefieldTags().isEmpty())
    }

    @Test
    fun recordsBothDecksMovesWithoutDisclosingUnrevealedIdentity() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "OWN_DECK_CARD", "DECK", 1)
        tracker.card(11, "PRIVATE_OPPONENT_CARD", "DECK", 2)
        tracker.card(12, "PUBLIC_CARD", "PLAY", 2, kind = "MINION")
        tracker.card(13, "", "DECK", 2)
        assertTrue(tracker.snapshot?.deckEvents.orEmpty().isEmpty())
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")

        tracker.power("TAG_CHANGE Entity=10 tag=ZONE value=HAND")
        tracker.power("TAG_CHANGE Entity=11 tag=ZONE value=HAND")
        tracker.power("HIDE_ENTITY - Entity=12 tag=ZONE value=DECK")
        tracker.power("TAG_CHANGE Entity=12 tag=ZONE value=DECK")
        tracker.power("TAG_CHANGE Entity=13 tag=CONTROLLER value=1")
        tracker.power("TAG_CHANGE Entity=13 tag=CONTROLLER value=1")

        val state = assertNotNull(tracker.snapshot)
        val pop = PowerLogDeckChangeKind.TAKEN
        val push = PowerLogDeckChangeKind.RETURNED
        assertEquals(
            listOf(
                PowerLogDeckEvent(10, 1, pop, 4, "OWN_DECK_CARD"),
                PowerLogDeckEvent(11, 2, pop, 4, null),
                PowerLogDeckEvent(12, 2, push, 4, "PUBLIC_CARD"),
                PowerLogDeckEvent(13, 2, pop, 4, null),
                PowerLogDeckEvent(13, 1, push, 4, null),
            ),
            state.deckEvents,
        )
        assertEquals(listOf(PowerLogDeckChange("OWN_DECK_CARD", pop, 4)), state.friendly.deckChanges)
        assertTrue(state.opponent.deckChanges.isEmpty())
        assertFalse(state.toString().contains("PRIVATE_OPPONENT_CARD"))
    }

    @Test
    fun tracksConfirmedPlaysIgnoresPowerTaskListAndRequiresFreshConstructedMetadata() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(14, "", "HAND", 2, kind = "MINION")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=3")
        tracker.power("BLOCK_START BlockType=PLAY Entity=14 EffectCardId= EffectIndex=0 Target=0")
        tracker.power("SHOW_ENTITY - Updating Entity=14 CardID=CS2_182")
        tracker.power("tag=ZONE value=PLAY")
        tracker.power("tag=ATK value=4")
        tracker.power("BLOCK_START BlockType=TRIGGER Entity=14 EffectCardId= EffectIndex=0 Target=0")
        tracker.power("BLOCK_END")
        assertTrue(tracker.snapshot?.opponent?.played.orEmpty().isEmpty())
        assertTrue(tracker.snapshot?.publicPlays.orEmpty().isEmpty())
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")
        tracker.power("BLOCK_END")
        tracker.accept("D 11:01:10.2495000 PowerTaskList.DebugPrintPower() -     CREATE_GAME")
        assertEquals(listOf("CS2_182"), tracker.snapshot?.opponent?.played?.map { it.cardId })
        assertEquals(listOf(PowerLogPublicPlay(2, 14, "CS2_182", 3, "HAND")), tracker.snapshot?.publicPlays)

        tracker.begin()
        assertNull(tracker.snapshot)
        tracker.feed("DebugPrintGame", "GameType=GT_ARENA")
        tracker.feed("DebugPrintGame", "FormatType=FT_WILD")
        assertNull(tracker.snapshot)
        tracker.begin()
        tracker.feed("DebugPrintGame", "GameType=GT_RANKED")
        assertNull(tracker.snapshot)
        tracker.feed("DebugPrintGame", "FormatType=FT_STANDARD")
        assertEquals(GameFormat.STANDARD, tracker.snapshot?.format)
        assertFalse(assertNotNull(tracker.snapshot).localPlayerIdentified)
        tracker.reset()
        assertNull(tracker.snapshot)
    }

    @Test
    fun calculatesHealthAndArmorOnlyForTheCurrentHeroWithKnownHealth() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "HERO_01", "PLAY", 1, kind = "HERO")
        tracker.power("TAG_CHANGE Entity=2 tag=HERO_ENTITY value=10")
        tracker.power("TAG_CHANGE Entity=10 tag=ARMOR value=3")
        assertNull(tracker.snapshot?.friendly?.heroEffectiveHealth)
        tracker.power("TAG_CHANGE Entity=10 tag=HEALTH value=30")
        tracker.power("TAG_CHANGE Entity=10 tag=DAMAGE value=8")
        assertEquals(25, tracker.snapshot?.friendly?.heroEffectiveHealth)

        tracker.power("TAG_CHANGE Entity=2 tag=HERO_ENTITY value=11")
        assertNull(tracker.snapshot?.friendly?.heroEffectiveHealth)
        tracker.card(11, "HERO_01x", "PLAY", 1, kind = "HERO")
        tracker.power("tag=45 value=20")
        tracker.power("tag=292 value=5")
        assertEquals(25, tracker.snapshot?.friendly?.heroEffectiveHealth)
        assertNull(tracker.snapshot?.opponent?.heroEffectiveHealth)
    }

    @Test
    fun preservesObservedPositionsAndCombatTagsForPublicEntities() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "HERO_01", "PLAY", 1, kind = "HERO")
        tracker.power("tag=ZONE_POSITION value=0")
        tracker.power("tag=HEALTH value=30")
        tracker.power("tag=DAMAGE value=8")
        tracker.power("tag=ARMOR value=3")
        tracker.power("tag=EXHAUSTED value=0")
        tracker.power("tag=NUM_TURNS_IN_PLAY value=4")
        tracker.power("TAG_CHANGE Entity=2 tag=HERO_ENTITY value=10")

        tracker.card(11, "DAMAGED_MINION", "PLAY", 2, kind = "MINION", attack = 2)
        tracker.power("tag=ZONE_POSITION value=3")
        tracker.power("tag=HEALTH value=5")
        tracker.power("tag=DAMAGE value=1")
        tracker.power("tag=EXHAUSTED value=1")
        tracker.power("tag=NUM_TURNS_IN_PLAY value=2")
        tracker.card(12, "OTHER_MINION", "PLAY", 2, kind = "MINION", attack = 1)
        tracker.power("tag=ZONE_POSITION value=1")

        val match = assertNotNull(tracker.snapshot)
        assertEquals(1, match.friendly.playerId)
        assertEquals(2, match.friendly.playerEntityId)
        assertEquals(2, match.opponent.playerId)
        assertEquals(3, match.opponent.playerEntityId)
        assertEquals("HERO_01", match.friendly.heroCardId)
        assertEquals(25, match.friendly.heroEffectiveHealth)
        assertEquals(PowerLogCombatState(0, 30, 8, 3, false, 4), match.friendly.heroCombatState)
        assertEquals(listOf(1, 3), match.opponent.board.map { it.combatState?.position })
        val damaged = match.opponent.board.last()
        assertEquals(4, damaged.health)
        assertEquals(PowerLogCombatState(3, 5, 1, 0, true, 2), damaged.combatState)
        assertNull(match.opponent.board.first().combatState?.exhausted)
    }

    @Test
    fun exposesOnlyTheUnambiguousInPlayHeroPower() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(20, "OLD_POWER", "PLAY", 1, kind = "10")
        assertEquals(PowerLogHeroPower(20, "OLD_POWER"), tracker.snapshot?.friendly?.heroPower)

        tracker.card(21, "NEW_POWER", "PLAY", 1, kind = "HERO_POWER")
        assertNull(tracker.snapshot?.friendly?.heroPower)
        tracker.power("TAG_CHANGE Entity=20 tag=ZONE value=GRAVEYARD")
        assertEquals(PowerLogHeroPower(21, "NEW_POWER"), tracker.snapshot?.friendly?.heroPower)

        tracker.card(23, "HIDDEN_POWER", "HAND", 2, kind = "HERO_POWER")
        assertNull(tracker.snapshot?.opponent?.heroPower)
        tracker.card(24, "", "PLAY", 2, kind = "HERO_POWER")
        assertEquals(PowerLogHeroPower(24, null), tracker.snapshot?.opponent?.heroPower)
    }

    @Test
    fun recordsOwnDeckTransitionsWithLateRevealAndKeepsEarlierIdentityWhenTransformed() {
        val tracker = constructed()
        tracker.card(10, "", "DECK", 2)
        tracker.card(11, "PRIVATE_ENEMY", "DECK", 1)
        tracker.card(12, "GENERATED_CARD", "SETASIDE", 2)
        tracker.power("TAG_CHANGE Entity=10 tag=ZONE value=HAND")
        tracker.power("SHOW_ENTITY - Updating Entity=10 CardID=STARTING_CARD")
        tracker.power("tag=ZONE value=HAND")
        tracker.power("HIDE_ENTITY - Entity=10 tag=ZONE value=DECK")
        tracker.power("TAG_CHANGE Entity=11 tag=ZONE value=HAND")
        tracker.power("TAG_CHANGE Entity=12 tag=ZONE value=HAND")
        assertTrue(tracker.snapshot?.friendly?.deckChanges.orEmpty().isEmpty())

        tracker.setFriendlyPlayerId(2)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")
        tracker.power("SHOW_ENTITY - Updating Entity=10 CardID=STARTING_CARD")
        tracker.power("TAG_CHANGE Entity=10 tag=ZONE value=HAND")
        tracker.power("CHANGE_ENTITY - Updating Entity=10 CardID=TRANSFORMED_CARD")
        tracker.power("tag=ZONE value=HAND")
        val result = assertNotNull(tracker.snapshot)
        assertEquals(
            listOf(
                PowerLogDeckChange("STARTING_CARD", PowerLogDeckChangeKind.TAKEN, 0),
                PowerLogDeckChange("STARTING_CARD", PowerLogDeckChangeKind.RETURNED, 0),
                PowerLogDeckChange("STARTING_CARD", PowerLogDeckChangeKind.TAKEN, 4),
            ),
            result.friendly.deckChanges,
        )
        assertTrue(result.opponent.deckChanges.isEmpty())
        assertFalse(result.toString().contains("PRIVATE_ENEMY"))
    }

    @Test
    fun removesRevealedCardsSetAsideFromTheDeckAndBalancesTheirReturn() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(13, "", "DECK", 1)
        tracker.card(14, "", "DECK", 1)
        tracker.card(15, "", "DECK", 1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=23")

        tracker.power("SHOW_ENTITY - Updating Entity=13 CardID=CATA_153")
        tracker.power("tag=ZONE value=SETASIDE")
        tracker.power("TAG_CHANGE Entity=13 tag=ZONE value=6")
        tracker.power("TAG_CHANGE Entity=14 tag=ZONE value=SETASIDE")
        tracker.power("SHOW_ENTITY - Updating Entity=14 CardID=CATA_565")
        tracker.power("tag=ZONE value=SETASIDE")
        assertEquals(1, tracker.snapshot?.friendly?.deckCount)

        tracker.power("TAG_CHANGE Entity=14 tag=ZONE value=DECK")
        tracker.power("HIDE_ENTITY - Entity=15 tag=ZONE value=SETASIDE")
        tracker.power("HIDE_ENTITY - Entity=15 tag=ZONE value=DECK")
        tracker.power("TAG_CHANGE Entity=15 tag=ZONE value=HAND")
        tracker.power("SHOW_ENTITY - Updating Entity=15 CardID=CATA_722")
        assertEquals(1, tracker.snapshot?.friendly?.deckCount)
        assertEquals(
            listOf(
                PowerLogDeckChange("CATA_153", PowerLogDeckChangeKind.TAKEN, 23),
                PowerLogDeckChange("CATA_565", PowerLogDeckChangeKind.TAKEN, 23),
                PowerLogDeckChange("CATA_565", PowerLogDeckChangeKind.RETURNED, 23),
                PowerLogDeckChange("CATA_722", PowerLogDeckChangeKind.TAKEN, 23),
            ),
            tracker.snapshot?.friendly?.deckChanges,
        )
    }

    @Test
    fun tracksCreatedDeckCardsAcrossRevealHideAndLaterDeparture() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(13, "STARTING_CARD", "DECK", 1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=26")
        tracker.card(353, "", "DECK", 1)
        tracker.power("TAG_CHANGE Entity=353 tag=DISPLAYED_CREATOR value=49")
        tracker.power("SHOW_ENTITY - Updating Entity=353 CardID=WW_372t")
        tracker.power("tag=ZONE value=DECK")
        tracker.power("tag=CREATOR value=49")
        tracker.power("HIDE_ENTITY - Entity=353 tag=ZONE value=DECK")
        tracker.power("FULL_ENTITY - Updating Entity=353 CardID=")
        tracker.power("tag=ZONE value=DECK")
        tracker.power("tag=CONTROLLER value=1")
        tracker.card(354, "WW_372t", "DECK", 1)
        tracker.power("tag=CREATOR value=49")
        tracker.card(355, "HIDDEN_ENEMY_INSERTION", "DECK", 2)
        tracker.power("tag=CREATOR value=49")
        assertEquals(3, tracker.snapshot?.friendly?.deckCount)
        assertEquals(2, tracker.snapshot?.friendly?.deckChanges?.size)

        tracker.power("TAG_CHANGE Entity=353 tag=ZONE value=GRAVEYARD")
        tracker.power("TAG_CHANGE Entity=355 tag=ZONE value=GRAVEYARD")
        val state = assertNotNull(tracker.snapshot)
        assertEquals(2, state.friendly.deckCount)
        assertEquals(
            listOf(
                PowerLogDeckChange("WW_372t", PowerLogDeckChangeKind.RETURNED, 26),
                PowerLogDeckChange("WW_372t", PowerLogDeckChangeKind.RETURNED, 26),
                PowerLogDeckChange("WW_372t", PowerLogDeckChangeKind.TAKEN, 26),
            ),
            state.friendly.deckChanges,
        )
        assertTrue(state.opponent.deckChanges.isEmpty())
        assertFalse(state.toString().contains("HIDDEN_ENEMY_INSERTION"))
    }

    @Test
    fun opponentHandShowsPositionAndTimingButOnlyNamesKnownReturnedCards() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "PRIVATE_OPENING", "DECK", 2)
        tracker.power("TAG_CHANGE Entity=10 tag=ZONE value=HAND")
        tracker.power("TAG_CHANGE Entity=10 tag=ZONE_POSITION value=1")
        tracker.card(11, "", "DECK", 2)
        tracker.card(12, "PUBLIC_MINION", "PLAY", 2, "MINION", 4)
        tracker.card(13, "PRIVATE_DISCARD", "HAND", 2)
        tracker.power("TAG_CHANGE Entity=13 tag=ZONE value=GRAVEYARD")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")
        tracker.power("TAG_CHANGE Entity=11 tag=ZONE value=HAND")
        tracker.power("TAG_CHANGE Entity=11 tag=ZONE_POSITION value=2")
        tracker.power("SHOW_ENTITY - Updating Entity=[entityName=hidden id=11 zone=HAND zonePos=2 cardId=PRIVATE_DRAW player=2] CardID=PRIVATE_SHOW")
        tracker.power("TAG_CHANGE Entity=12 tag=ZONE value=HAND")
        tracker.power("TAG_CHANGE Entity=12 tag=ZONE_POSITION value=3")
        tracker.power("TAG_CHANGE Entity=13 tag=ZONE value=HAND")

        val side = assertNotNull(tracker.snapshot).opponent
        assertEquals(PowerLogHandMark(10, 1, 0, PowerLogHandOrigin.OPENING), side.handMarks.single { it.entityId == 10 })
        assertEquals(PowerLogHandMark(11, 2, 4, PowerLogHandOrigin.DRAWN), side.handMarks.single { it.entityId == 11 })
        assertEquals(PowerLogHandMark(12, 3, 4, PowerLogHandOrigin.RETURNED, "PUBLIC_MINION"), side.handMarks.single { it.entityId == 12 })
        assertEquals(PowerLogHandMark(13, null, 4, PowerLogHandOrigin.RETURNED), side.handMarks.single { it.entityId == 13 })
        assertFalse(side.toString().contains("PRIVATE_"))

        tracker.power("CHANGE_ENTITY - Updating Entity=12 CardID=PRIVATE_MORPH")
        assertNull(tracker.snapshot?.opponent?.handMarks?.single { it.entityId == 12 }?.cardId)
        tracker.power("TAG_CHANGE Entity=11 tag=ZONE value=DECK")
        assertEquals(3, tracker.snapshot?.opponent?.handMarks?.size)
    }

    @Test
    fun recognizesHiddenOpeningCoinOnlyInTheSecondPlayersOpeningDeal() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.power("BLOCK_START BlockType=TRIGGER Entity=1 EffectCardId= EffectIndex=-1 Target=0 SubOption=-1")
        tracker.power("TAG_CHANGE Entity=2 tag=FIRST_PLAYER value=1")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=1")
        tracker.openingDraws(2, 20..23, shown = false)
        tracker.power("BLOCK_START BlockType=POWER Entity=1 EffectCardId= EffectIndex=0 Target=0 SubOption=-1")
        tracker.card(24, "", "HAND", 2)
        tracker.power("tag=ZONE_POSITION value=5")
        tracker.power("BLOCK_END")
        tracker.card(25, "", "HAND", 2)
        tracker.power("tag=ZONE_POSITION value=5")
        tracker.power("BLOCK_END")

        val marks = assertNotNull(tracker.snapshot).opponent.handMarks.associateBy { it.entityId }
        assertNull(marks.getValue(24).cardId)
        assertEquals(PowerLogHandMark(25, 5, 1, PowerLogHandOrigin.COIN, "GAME_005"), marks.getValue(25))

        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        tracker.power("TAG_CHANGE Entity=25 tag=ZONE_POSITION value=4")
        assertEquals("GAME_005", tracker.snapshot?.opponent?.handMarks?.single { it.entityId == 25 }?.cardId)
        tracker.power("TAG_CHANGE Entity=25 tag=ZONE value=PLAY")
        assertTrue(tracker.snapshot?.opponent?.handMarks.orEmpty().none { it.entityId == 25 })
    }

    @Test
    fun labelsGeneratedOpponentCardsAndCoinsWithoutExposingHiddenIdentities() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=6")
        tracker.card(50, "BG31_BOB", "PLAY", 2, "MINION")
        tracker.card(51, "PRIVATE_PARENT", "HAND", 2)
        tracker.card(52, "PRIVATE_GENERATED", "HAND", 2)
        tracker.power("TAG_CHANGE Entity=52 tag=DISPLAYED_CREATOR value=50")
        tracker.card(53, "TTN_COIN2", "HAND", 2)
        tracker.power("tag=CREATOR value=50")
        tracker.card(54, "", "HAND", 2)
        tracker.power("tag=CREATOR value=51")
        tracker.card(55, "", "HAND", 2)
        tracker.power("tag=COIN_CARD value=1")

        val side = assertNotNull(tracker.snapshot).opponent
        val marks = side.handMarks.associateBy { it.entityId }
        assertEquals(PowerLogHandMark(52, null, 6, PowerLogHandOrigin.GENERATED, creatorCardId = "BG31_BOB"), marks.getValue(52))
        assertEquals(PowerLogHandMark(53, null, 6, PowerLogHandOrigin.GENERATED, "GAME_005", "BG31_BOB"), marks.getValue(53))
        assertEquals(PowerLogHandMark(54, null, 6, PowerLogHandOrigin.GENERATED), marks.getValue(54))
        assertEquals(PowerLogHandMark(55, null, 6, PowerLogHandOrigin.GENERATED, "GAME_005"), marks.getValue(55))
        assertFalse(side.toString().contains("PRIVATE_"))
    }

    @Test
    fun separatesBoardAndActiveAttackAndCountsHeroWeaponOnce() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "HERO_01", "PLAY", 1, "HERO", 3)
        tracker.power("tag=EXHAUSTED value=0")
        tracker.card(11, "WEAPON_CARD", "PLAY", 1, "7", 2)
        tracker.power("tag=DURABILITY value=3")
        tracker.power("tag=HEALTH value=5")
        tracker.power("tag=DAMAGE value=1")
        tracker.card(12, "WINDFURY_CARD", "PLAY", 1, "MINION", 4)
        tracker.power("tag=EXHAUSTED value=0")
        tracker.power("tag=WINDFURY value=1")
        tracker.card(13, "FROZEN_CARD", "PLAY", 1, "MINION", 3)
        tracker.power("tag=EXHAUSTED value=0")
        tracker.power("tag=FROZEN value=1")
        tracker.card(14, "UNKNOWN_READY", "PLAY", 1, "MINION", 6)
        tracker.card(15, "LOCATION_CARD", "PLAY", 1, "39")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=5")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        tracker.power("TAG_CHANGE Entity=12 tag=NUM_ATTACKS_THIS_TURN value=1")
        tracker.card(16, "RUSH_CARD", "PLAY", 1, "MINION", 2)
        tracker.power("tag=EXHAUSTED value=0")
        tracker.power("tag=RUSH value=1")
        tracker.card(17, "NEW_CARD", "PLAY", 1, "MINION", 5)
        tracker.power("tag=EXHAUSTED value=0")

        val side = assertNotNull(tracker.snapshot).friendly
        assertEquals(20, side.boardAttack)
        assertEquals(9, side.availableAttack)
        assertEquals(15, side.availableAttackUpperBound)
        assertEquals(3, side.heroAttack)
        assertEquals(2, side.weaponAttack)
        assertEquals(2, side.weaponDurability)
        assertEquals(6, side.boardOccupiedSlots)
        val rush = side.board.single { it.cardId == "RUSH_CARD" }
        assertEquals(false, rush.canAttackHero)
        assertEquals(true, rush.canAttackMinions)
        assertEquals(1, side.board.single { it.cardId == "WINDFURY_CARD" }.remainingAttacks)
        assertEquals(0, side.board.single { it.cardId == "FROZEN_CARD" }.remainingAttacks)

        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=0")
        tracker.power("TAG_CHANGE Entity=3 tag=CURRENT_PLAYER value=1")
        assertEquals(false, tracker.snapshot?.friendly?.isCurrentTurn)
        assertNull(tracker.snapshot?.friendly?.availableAttack)
        assertEquals(true, tracker.snapshot?.opponent?.isCurrentTurn)
    }

    @Test
    fun retainsEquippedWeaponWithDurabilityReportedAsHealth() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(10, "HERO_02", "PLAY", 1, "HERO")
        tracker.card(43, "BE_349", "PLAY", 1, "WEAPON", 2)
        tracker.power("tag=HEALTH value=2")
        tracker.power("TAG_CHANGE Entity=10 tag=MAIN_HAND_WEAPON_ENTITY value=43")
        tracker.power("TAG_CHANGE Entity=43 tag=DAMAGE value=1")

        val side = assertNotNull(tracker.snapshot).friendly
        assertEquals(43, side.weaponEntityId)
        assertEquals("BE_349", side.weaponCardId)
        assertEquals(2, side.weaponAttack)
        assertEquals(1, side.weaponDurability)
        assertEquals(1, side.weaponCombatState?.damage)

        tracker.power("TAG_CHANGE Entity=43 tag=DAMAGE value=2")
        assertEquals(0, tracker.snapshot?.friendly?.weaponDurability)
        tracker.power("TAG_CHANGE Entity=10 tag=MAIN_HAND_WEAPON_ENTITY value=0")
        tracker.power("TAG_CHANGE Entity=43 tag=ZONE value=GRAVEYARD")
        assertNull(tracker.snapshot?.friendly?.weaponEntityId)
        assertNull(tracker.snapshot?.friendly?.weaponDurability)
    }

    @Test
    fun derivesCurrentManaWithoutDoubleCountingOverloadAndUsesPositiveOptionsOnly() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(15, "HAND_CARD", "HAND", 1)
        tracker.power("tag=COST value=4")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=5")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_READY")
        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        tracker.power("TAG_CHANGE Entity=2 tag=RESOURCES value=5")
        tracker.power("TAG_CHANGE Entity=2 tag=RESOURCES_USED value=2")
        tracker.power("TAG_CHANGE Entity=2 tag=OVERLOAD_LOCKED value=2")
        tracker.power("TAG_CHANGE Entity=2 tag=TEMP_RESOURCES value=1")
        assertNull(tracker.snapshot?.friendly?.availableMana)

        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        assertEquals(4, tracker.snapshot?.friendly?.availableMana)
        assertEquals(4, tracker.snapshot?.friendly?.hand?.single()?.cost)
        tracker.feed("DebugPrintOptions", "id=5")
        val option = "option 1 type=POWER mainEntity=[entityName=Card id=15 zone=HAND zonePos=1 cardId=HAND_CARD player=1]"
        tracker.feed("DebugPrintOptions", "$option error=REQ_ENOUGH_MANA errorParam=")
        assertNull(tracker.snapshot?.friendly?.hand?.single()?.playOptionReported)
        tracker.feed("DebugPrintOptions", "$option error=NONE errorParam=")
        assertEquals(true, tracker.snapshot?.friendly?.hand?.single()?.playOptionReported)
        tracker.power("TAG_CHANGE Entity=15 tag=COST value=3")
        assertEquals(3, tracker.snapshot?.friendly?.hand?.single()?.cost)
        assertNull(tracker.snapshot?.friendly?.hand?.single()?.playOptionReported)
    }

    @Test
    fun retainsObservedActionOptionsAndTheirDistinctTargetsInLogOrder() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(15, "DUPLICATE_SPELL", "HAND", 1)
        tracker.card(16, "DUPLICATE_SPELL", "HAND", 1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=5")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")

        tracker.feed("DebugPrintOptions", "id=1")
        val prefix = assertNotNull(tracker.snapshot?.actionOptions)
        assertTrue(prefix.options.isEmpty())
        tracker.feed("DebugPrintOptions", "target 0 entity=99 error=NONE errorParam=")
        tracker.feed("DebugPrintOptions", "option 0 type=END_TURN mainEntity= error=INVALID errorParam=")
        tracker.feed("DebugPrintOptions", "option 1 type=POWER mainEntity=[entityName=Card id=15 zone=HAND player=1] error=NONE errorParam=")
        tracker.feed("DebugPrintOptions", "target 0 entity=25 error=NONE errorParam=")
        tracker.feed("DebugPrintOptions", "target 1 entity=[entityName=Enemy id=26 zone=PLAY player=2] error=REQ_ENEMY_TARGET errorParam=7")
        tracker.feed("DebugPrintOptions", "option 2 type=POWER mainEntity=[entityName=Card id=16 zone=HAND player=1] error=NONE errorParam=")
        tracker.feed("DebugPrintOptions", "subOption 0 entity=17 error=NONE errorParam=")
        tracker.feed("DebugPrintOptions", "target 0 entity=26 error=NONE errorParam=")
        tracker.feed("DebugPrintOptions", "subOption 1 entity=18")
        tracker.feed("DebugPrintOptions", "target 0 entity=27 error=NONE errorParam=")

        val options = assertNotNull(tracker.snapshot?.actionOptions)
        assertEquals(1, options.packetId)
        assertEquals(prefix.revision, options.revision)
        assertTrue(prefix.options.isEmpty())
        assertEquals(listOf(0, 1, 2), options.options.map { it.index })
        assertEquals(listOf(null, 15, 16), options.options.map { it.entityId })
        assertEquals(listOf(15, 16), tracker.snapshot?.friendly?.hand?.map { it.entityId })
        assertEquals("END_TURN", options.options[0].type)
        assertEquals("INVALID", options.options[0].error)
        assertTrue(options.options[0].targets.isEmpty())
        assertEquals(
            listOf(PowerLogActionTarget(0, 25, "NONE", null), PowerLogActionTarget(1, 26, "REQ_ENEMY_TARGET", 7)),
            options.options[1].targets,
        )
        assertEquals(
            listOf(
                PowerLogActionSubOption(0, 17, "NONE", null, listOf(PowerLogActionTarget(0, 26, "NONE", null))),
                PowerLogActionSubOption(1, 18, null, null, listOf(PowerLogActionTarget(0, 27, "NONE", null))),
            ),
            options.options[2].subOptions,
        )
    }

    @Test
    fun replacesRepeatedPacketIdsAndInvalidatesBeforeTheNextDecision() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.card(15, "LOCAL_SPELL", "HAND", 1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=3")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        val option = "option 1 type=POWER mainEntity=[entityName=Card id=15 zone=HAND player=1] error=NONE errorParam="

        tracker.feed("DebugPrintOptions", "id=1")
        tracker.feed("DebugPrintOptions", option)
        val first = assertNotNull(tracker.snapshot?.actionOptions)
        assertEquals(true, tracker.snapshot?.friendly?.hand?.single()?.playOptionReported)

        tracker.feed("SendOption", "selectedOption=1 selectedSubOption=-1 selectedTarget=0 selectedPosition=0")
        tracker.feed("DebugPrintOptions", "target 0 entity=7 error=NONE errorParam=")
        assertNull(tracker.snapshot?.actionOptions)
        assertNull(tracker.snapshot?.friendly?.hand?.single()?.playOptionReported)

        tracker.feed("DebugPrintOptions", "id=1")
        val next = assertNotNull(tracker.snapshot?.actionOptions)
        assertEquals(first.packetId, next.packetId)
        assertTrue(next.revision > first.revision)
        assertTrue(next.options.isEmpty())
        tracker.feed("DebugPrintOptions", option)
        tracker.power("TAG_CHANGE Entity=15 tag=COST value=2")
        assertNull(tracker.snapshot?.actionOptions)

        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=0")
        tracker.power("TAG_CHANGE Entity=3 tag=CURRENT_PLAYER value=1")
        tracker.feed("DebugPrintOptions", "id=1")
        tracker.feed("DebugPrintOptions", option)
        assertNull(tracker.snapshot?.actionOptions)
    }

    @Test
    fun keepsLocalDiscoverCandidatesUntilTheMatchingSelectionIsSentOrConfirmed() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=3")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_START_TRIGGERS")

        tracker.feed("DebugPrintEntityChoices", "id=7 Player=2 TaskList= ChoiceType=GENERAL CountMin=1 CountMax=1")
        tracker.feed("DebugPrintEntityChoices", "Source=19")
        tracker.feed("DebugPrintEntityChoices", "Entities[2]=[entityName=Third id=32 cardId=CHOICE_C player=1]")
        tracker.feed("DebugPrintEntityChoices", "Entities[0]=[entityName=First id=30 cardId=CHOICE_A player=1]")
        tracker.feed("DebugPrintEntityChoices", "Entities[1]=[entityName=Second id=31 cardId=CHOICE_B player=1]")
        val choice = assertNotNull(tracker.snapshot?.generalChoice)
        assertEquals(19, choice.sourceEntityId)
        assertEquals(1, choice.maxSelections)
        assertEquals(listOf(30, 31, 32), choice.options.map { it.entityId })
        assertEquals(listOf("CHOICE_A", "CHOICE_B", "CHOICE_C"), choice.options.map { it.cardId })

        tracker.feed("DebugPrintEntityChoices", "id=7 Player=3 TaskList=11 ChoiceType=GENERAL CountMin=1 CountMax=1")
        tracker.feed("DebugPrintEntityChoices", "Entities[0]=[entityName=Unknown id=40 cardId= player=2]")
        tracker.feed("DebugPrintEntitiesChosen", "id=7 Player=3 EntitiesCount=1")
        tracker.feed("SendChoices", "id=4 ChoiceType=GENERAL")
        assertEquals(choice, tracker.snapshot?.generalChoice)

        tracker.feed("DebugPrintEntitiesChosen", "id=7 Player=2 EntitiesCount=1")
        assertNull(tracker.snapshot?.generalChoice)

        tracker.feed("DebugPrintEntityChoices", "id=7 Player=2 TaskList=11 ChoiceType=GENERAL CountMin=1 CountMax=1")
        val next = assertNotNull(tracker.snapshot?.generalChoice)
        assertTrue(next.revision > choice.revision)
        tracker.feed("SendChoices", "id=7 ChoiceType=GENERAL")
        assertNull(tracker.snapshot?.generalChoice)
    }

    @Test
    fun usesVisibleLocalCandidatesWhenTheChoicePlayerNameIsUnresolved() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=3")
        tracker.feed("DebugPrintEntityChoices", "id=5 Player=Unresolved TaskList=1 ChoiceType=GENERAL CountMin=1 CountMax=1")
        tracker.feed("DebugPrintEntityChoices", "Source=19")
        assertNull(tracker.snapshot?.generalChoice)
        tracker.feed("DebugPrintEntityChoices", "Entities[0]=[entityName=Hidden id=30 cardId= player=2]")
        tracker.feed("DebugPrintEntityChoices", "Entities[1]=[entityName=Other id=31 cardId=OTHER player=1]")
        assertNull(tracker.snapshot?.generalChoice)

        tracker.feed("DebugPrintEntityChoices", "id=6 Player=Unresolved TaskList=1 ChoiceType=GENERAL CountMin=1 CountMax=1")
        tracker.feed("DebugPrintEntityChoices", "Entities[0]=[entityName=First id=32 cardId=CHOICE_A player=1]")
        val choice = assertNotNull(tracker.snapshot?.generalChoice)
        assertEquals(6, choice.choiceId)
        tracker.feed("DebugPrintEntitiesChosen", "id=6 Player=Unresolved EntitiesCount=1")
        assertNull(tracker.snapshot?.generalChoice)
    }

    @Test
    fun usesTurnTransitionsWhenPlayerNamesArriveAfterMetadata() {
        val tracker = constructed()
        tracker.setFriendlyPlayerId(1)
        tracker.feed("DebugPrintGame", "PlayerID=1, PlayerName=UNKNOWN HUMAN PLAYER")
        tracker.feed("DebugPrintGame", "PlayerID=2, PlayerName=UNKNOWN HUMAN PLAYER")
        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=1")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_READY")
        tracker.power("TAG_CHANGE Entity=first name tag=RESOURCES value=1")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        assertEquals(1, tracker.snapshot?.friendly?.availableMana)

        tracker.power("TAG_CHANGE Entity=first name tag=CURRENT_PLAYER value=0")
        tracker.power("TAG_CHANGE Entity=second name tag=CURRENT_PLAYER value=1")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=2")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_READY")
        tracker.power("TAG_CHANGE Entity=second name tag=RESOURCES value=2")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        val snapshot = assertNotNull(tracker.snapshot)
        assertEquals(false, snapshot.friendly.isCurrentTurn)
        assertEquals(2, snapshot.opponent.availableMana)
        assertFalse(snapshot.toString().contains("name"))
    }

    @Test
    fun presentsOnlyLocallyRevealedOpeningCardsBeforeTheSecondPlayerSubmits() {
        val tracker = constructed()
        tracker.card(30, "HERO_01", "PLAY", 1, "HERO")
        tracker.card(31, "HERO_08", "PLAY", 2, "HERO")
        tracker.openingDraws(1, 10..12, shown = false)
        tracker.openingDraws(2, 20..23, shown = true)
        tracker.card(24, "TSC_COIN2", "HAND", 2)
        tracker.openingChoices(1, 1, 10..12)
        tracker.openingChoices(2, 2, 20..24)
        assertNull(tracker.snapshot?.openingChoice)

        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN")
        val snapshot = assertNotNull(tracker.snapshot)
        assertFalse(snapshot.localPlayerIdentified)
        assertTrue(snapshot.friendly.hand.isEmpty())
        assertTrue(snapshot.opponent.hand.isEmpty())
        assertTrue(snapshot.friendly.deckChanges.isEmpty())
        assertTrue(snapshot.opponent.deckChanges.isEmpty())
        val opening = assertNotNull(snapshot.openingChoice)
        assertEquals(2, opening.choiceId)
        assertEquals(HsClass.MAGE, opening.friendlyHeroClass)
        assertEquals(HsClass.WARRIOR, opening.opponentHeroClass)
        assertEquals((20..23).map { "SHOWN_$it" }, opening.cards.map { it.cardId })
        assertEquals(listOf(1, 2, 3, 4), opening.cards.map { it.position })
        assertEquals(listOf(1, 1, 1, 1), opening.cards.map { it.cost })
        assertFalse(snapshot.toString().contains("PRIVATE_"))
        assertFalse(snapshot.toString().contains("TSC_COIN2"))

        tracker.feed("SendChoices", "id=2 ChoiceType=MULLIGAN")
        assertNull(tracker.snapshot?.openingChoice)
        assertTrue(assertNotNull(tracker.snapshot).localPlayerIdentified)
        assertTrue(tracker.snapshot?.opponent?.hand.orEmpty().isEmpty())
    }

    @Test
    fun waitsForCompleteUnambiguousOpeningEvidenceAndClearsWhenThePhaseEnds() {
        val tracker = constructed()
        tracker.openingDraws(1, 10..12, shown = true)
        tracker.openingDraws(2, 20..22, shown = false)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=4")
        tracker.openingChoices(1, 1, 10..12)
        assertNull(tracker.snapshot?.openingChoice)
        tracker.openingChoices(2, 2, 20..22, declaredPlayer = "999")
        assertNull(tracker.snapshot?.openingChoice)
        tracker.openingChoices(2, 2, 20..22)
        assertEquals(3, tracker.snapshot?.openingChoice?.cards?.size)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_READY")
        assertNull(tracker.snapshot?.openingChoice)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN")
        assertNull(tracker.snapshot?.openingChoice)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE")
        assertTrue(assertNotNull(tracker.snapshot).gameComplete)
    }

    @Test
    fun preservesMatchAndRewindsEntitiesAcrossResetGame() {
        val tracker = constructed()
        tracker.feed("DebugPrintGame", "PlayerID=1, PlayerName=PlayerOne#1001")
        tracker.feed("DebugPrintGame", "PlayerID=2, PlayerName=PlayerTwo#2002")
        tracker.openingDraws(1, 10..12, shown = true)
        tracker.openingDraws(2, 20..22, shown = false)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN")
        tracker.openingChoices(1, 1, 10..12)
        tracker.openingChoices(2, 2, 20..22)
        tracker.feed("SendChoices", "id=1 ChoiceType=MULLIGAN")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=11")
        tracker.power("TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        tracker.card(10, "CARD_A", "PLAY", 1, kind = "MINION", attack = 3)
        tracker.power("TAG_CHANGE Entity=10 tag=DAMAGE value=2")
        assertEquals(2, tracker.snapshot?.friendly?.board?.single()?.combatState?.damage)

        tracker.power("BLOCK_START BlockType=GAME_RESET Entity=[entityName=Rewind id=132 zone=PLAY zonePos=0 cardId=TIME_014 player=2] EffectCardId=System.Collections.Generic.List`1[System.String] EffectIndex=-1 Target=0 SubOption=-1")
        tracker.power("    RESET_GAME")
        tracker.power("    FULL_ENTITY - Updating PlayerOne CardID=")
        tracker.power("        tag=CONTROLLER value=1")
        tracker.power("        tag=CARDTYPE value=PLAYER")
        tracker.power("        tag=PLAYER_ID value=1")
        tracker.power("        tag=RESOURCES value=5")
        tracker.power("        tag=ENTITY_ID value=2")
        tracker.power("        tag=CURRENT_PLAYER value=1")
        tracker.power("    FULL_ENTITY - Updating PlayerTwo CardID=")
        tracker.power("        tag=CONTROLLER value=2")
        tracker.power("        tag=CARDTYPE value=PLAYER")
        tracker.power("        tag=PLAYER_ID value=2")
        tracker.power("        tag=RESOURCES value=5")
        tracker.power("        tag=ENTITY_ID value=3")
        tracker.power("    FULL_ENTITY - Updating [entityName=CardA id=10 zone=HAND zonePos=1 cardId=CARD_A player=1] CardID=CARD_A")
        tracker.power("        tag=ZONE value=HAND")
        tracker.power("        tag=CONTROLLER value=1")
        tracker.power("        tag=CARDTYPE value=MINION")
        tracker.power("        tag=ATK value=3")
        tracker.power("        tag=HEALTH value=4")
        tracker.power("BLOCK_END")

        val rewound = assertNotNull(tracker.snapshot)
        assertTrue(rewound.localPlayerIdentified)
        assertEquals(11, rewound.turn)
        assertEquals(true, rewound.friendly.isCurrentTurn)
        assertTrue(rewound.friendly.board.isEmpty())
        assertEquals(1, rewound.friendly.hand.size)
        assertEquals("CARD_A", rewound.friendly.hand.single().cardId)
    }

    @Test
    fun withholdsOpeningSelectionWhenBothSidesHaveRevealedDraws() {
        val tracker = constructed()
        tracker.openingDraws(1, 10..12, shown = true)
        tracker.openingDraws(2, 20..22, shown = true)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN")
        tracker.openingChoices(1, 1, 10..12)
        tracker.openingChoices(2, 2, 20..22)
        assertNull(tracker.snapshot?.openingChoice)
        assertFalse(assertNotNull(tracker.snapshot).localPlayerIdentified)
    }

    private fun PowerLogTracker.openingDraws(player: Int, ids: IntRange, shown: Boolean) {
        for (id in ids) {
            card(id, if (shown) "" else "PRIVATE_$id", "DECK", player)
            if (shown) {
                power("SHOW_ENTITY - Updating Entity=$id CardID=SHOWN_$id")
                power("tag=ZONE value=HAND")
                power("tag=COST value=1")
            } else {
                power("TAG_CHANGE Entity=$id tag=ZONE value=HAND")
            }
        }
    }

    private fun PowerLogTracker.openingChoices(id: Int, player: Int, entities: IntRange, declaredPlayer: String = "UNKNOWN HUMAN PLAYER") {
        val stream = "DebugPrintEntityChoices"
        feed(stream, "id=$id Player=$declaredPlayer TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=${entities.count()}")
        feed(stream, "Source=GameEntity")
        for ((index, entity) in entities.withIndex()) {
            feed(stream, "Entities[$index]=[entityName=UNKNOWN ENTITY [cardType=INVALID] id=$entity zone=DECK zonePos=0 cardId= player=$player]")
        }
    }

    private fun constructed(captureCombatTags: Boolean = false) = PowerLogTracker(captureCombatTags).apply {
        begin()
        feed("DebugPrintGame", "GameType=GT_RANKED")
        feed("DebugPrintGame", "FormatType=FT_WILD")
    }

    private fun PowerLogTracker.begin() {
        power("CREATE_GAME")
        power("GameEntity EntityID=1")
        power("Player EntityID=2 PlayerID=1 GameAccountId=[hi=123 lo=123]")
        power("Player EntityID=3 PlayerID=2 GameAccountId=[hi=456 lo=456]")
    }

    private fun PowerLogTracker.card(
        id: Int,
        card: String,
        zone: String,
        controller: Int,
        kind: String = "SPELL",
        attack: Int? = null,
    ) {
        power("FULL_ENTITY - Creating ID=$id CardID=$card")
        power("tag=ZONE value=$zone")
        power("tag=CONTROLLER value=$controller")
        power("tag=CARDTYPE value=$kind")
        if (attack != null) power("tag=ATK value=$attack")
    }

    private fun PowerLogTracker.power(message: String) = feed("DebugPrintPower", message)

    private fun PowerLogTracker.feed(stream: String, message: String) =
        accept("D 11:01:10.2495000 GameState.$stream() -   $message")
}
