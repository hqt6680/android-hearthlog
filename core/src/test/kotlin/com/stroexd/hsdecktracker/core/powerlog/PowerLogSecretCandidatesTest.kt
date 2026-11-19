package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerLogSecretCandidatesTest {
    private val counter = secret(121, "EX1_287", "法术反制", "MAGE", "CORE")
    private val mirror = secret(122, "EX1_294", "镜像实体", "MAGE", "CORE")
    private val ice = secret(123, "EX1_295", "寒冰屏障", "MAGE", "EXPERT1")
    private val explosive = secret(124, "EX1_610", "爆炸陷阱", "HUNTER", "CORE")
    private val db = CardDatabase(listOf(counter, mirror, ice, explosive), locale = "zhCN")

    @Test
    fun originalEnemyDeckSecretsFollowClassAndFormatWithoutExposingTheHiddenIdentity() {
        val tracker = tracker()
        tracker.card(20, "", "DECK", 2)
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")
        tracker.power("TAG_CHANGE Entity=20 tag=ZONE value=HAND")
        tracker.power("BLOCK_START BlockType=PLAY Entity=20 EffectCardId= EffectIndex=0 Target=0")
        tracker.power("SHOW_ENTITY - Updating Entity=20 CardID=${counter.id}")
        tracker.power("tag=ZONE value=PLAY")
        tracker.power("TAG_CHANGE Entity=20 tag=ZONE value=SECRET")
        tracker.power("BLOCK_END")

        val match = assertNotNull(tracker.snapshot)
        assertEquals(HsClass.MAGE, match.opponent.secretObservations.single().deckClass)
        assertNull(match.opponent.secretObservations.single().revealedCardId)
        assertFalse(match.toString().contains(counter.id))
        assertEquals(setOf(counter, mirror), activeOpponentSecretCandidates(match, db).toSet())
    }

    @Test
    fun possibleGeneratedCardsAreNotExcludedByTheEnemyClassOrStandardRotation() {
        val tracker = tracker()
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")
        tracker.card(20, "", "SECRET", 2)
        val match = assertNotNull(tracker.snapshot)
        val candidates = activeOpponentSecretCandidates(match, db)
        assertTrue(counter in candidates)
        assertTrue(ice in candidates)
        assertTrue(explosive in candidates)
        assertTrue(candidates.any { it.id == "AV_113t1" && "伤害" in it.plainText })

        tracker.power("TAG_CHANGE Entity=20 tag=ZONE value=GRAVEYARD")
        assertTrue(activeOpponentSecretCandidates(assertNotNull(tracker.snapshot), db).isEmpty())
    }

    @Test
    fun knownTransferredSecretRemainsKnownButOurOwnSecretIsNotAnEnemyCandidate() {
        val tracker = tracker()
        tracker.card(20, explosive.id, "SECRET", 1)
        assertTrue(activeOpponentSecretCandidates(assertNotNull(tracker.snapshot), db).isEmpty())
        tracker.power("TAG_CHANGE Entity=20 tag=CONTROLLER value=2")
        val match = assertNotNull(tracker.snapshot)
        assertEquals(explosive.id, match.opponent.secretObservations.single().revealedCardId)
        assertEquals(listOf(explosive), activeOpponentSecretCandidates(match, db))
        assertTrue(activeOpponentSecretCandidates(match.copy(localPlayerIdentified = false), db).isEmpty())

        tracker.power("CHANGE_ENTITY - Updating Entity=20 CardID=${counter.id}")
        val changed = assertNotNull(tracker.snapshot)
        assertNull(changed.opponent.secretObservations.single().revealedCardId)
        assertFalse(changed.toString().contains(counter.id))
    }

    @Test
    fun identifiesTheSpecificGeneratedPoolWhenTavishIsAVisibleCreator() {
        val tracker = tracker()
        tracker.power("TAG_CHANGE Entity=GameEntity tag=TURN value=4")
        tracker.card(19, "AV_113", "PLAY", 2, "HERO")
        tracker.card(20, "", "SECRET", 2)
        tracker.power("tag=CREATOR value=19")
        val match = assertNotNull(tracker.snapshot)
        assertEquals("AV_113", match.opponent.secretObservations.single().creatorCardId)
        val candidates = activeOpponentSecretCandidates(match, db)
        assertEquals(9, candidates.size)
        assertTrue(candidates.all { it.name.startsWith("强化") && it.plainText.isNotBlank() })
    }

    private fun tracker() = PowerLogTracker().apply {
        power("CREATE_GAME")
        power("GameEntity EntityID=1")
        power("Player EntityID=2 PlayerID=1 GameAccountId=[hi=1 lo=1]")
        power("Player EntityID=3 PlayerID=2 GameAccountId=[hi=2 lo=2]")
        accept("D 11:01:10.2495000 GameState.DebugPrintGame() - GameType=GT_RANKED")
        accept("D 11:01:10.2495000 GameState.DebugPrintGame() - FormatType=FT_STANDARD")
        setFriendlyPlayerId(1)
        card(10, "HERO_08", "PLAY", 2, "HERO")
    }

    private fun PowerLogTracker.card(id: Int, cardId: String, zone: String, controller: Int, kind: String = "SPELL") {
        power("FULL_ENTITY - Creating ID=$id CardID=$cardId")
        power("tag=ZONE value=$zone")
        power("tag=CONTROLLER value=$controller")
        power("tag=CARDTYPE value=$kind")
    }

    private fun PowerLogTracker.power(message: String) =
        accept("D 11:01:10.2495000 GameState.DebugPrintPower() - $message")

    private fun secret(dbf: Int, id: String, name: String, heroClass: String, set: String) =
        Card(dbf, id, name = name, set = set, cardClass = heroClass, collectible = true,
            type = "SPELL", mechanics = listOf("SECRET"), text = "奥秘：触发效果。")
}
