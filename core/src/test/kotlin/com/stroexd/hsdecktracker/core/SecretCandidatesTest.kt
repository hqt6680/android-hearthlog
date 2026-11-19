package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.opponentSecretCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretCandidatesTest {
    @Test
    fun filtersFormatClassAndHiddenCorePrintings() {
        val original = secret(1, "Mirror", "EXPERT1")
        val realCore = secret(2, "Mirror", "CORE")
        val rotated = secret(3, "Trap", "NAXX")
        val dual = secret(4, "Dual", "TIME_TRAVEL").copy(
            cardClass = "ROGUE",
            classes = listOf("ROGUE", "MAGE"),
        )
        val hiddenCore = secret(5, "Removed from Core", "CORE_HIDDEN")
        val otherClass = secret(6, "Hunter secret", "CORE").copy(cardClass = "HUNTER")
        val token = secret(7, "Generated", "CORE").copy(collectible = false)
        val nonSecret = secret(8, "Secret synergy", "CORE").copy(mechanics = listOf("BATTLECRY"))
        val outsideConstructed = secret(9, "Brawl", "TB")
        val cards = CardDatabase(
            listOf(original, realCore, rotated, dual, hiddenCore, otherClass, token, nonSecret, outsideConstructed),
        )

        assertEquals(listOf(dual, realCore), opponentSecretCandidates(cards, HsClass.MAGE, GameFormat.STANDARD))
        assertEquals(listOf(dual, realCore, rotated), opponentSecretCandidates(cards, HsClass.MAGE, GameFormat.WILD))
        val override = FormatRules(standardOverrides = mapOf("TIME_TRAVEL" to false))
        assertEquals(listOf(realCore), opponentSecretCandidates(cards, HsClass.MAGE, GameFormat.STANDARD, override))
        assertTrue(opponentSecretCandidates(cards, HsClass.UNKNOWN, GameFormat.WILD).isEmpty())
        assertTrue(opponentSecretCandidates(cards, HsClass.MAGE, GameFormat.TWIST).isEmpty())
    }

    private fun secret(id: Int, name: String, set: String) = Card(
        dbfId = id,
        id = "secret_$id",
        name = name,
        cost = 3,
        type = "SPELL",
        cardClass = "MAGE",
        set = set,
        mechanics = listOf("SECRET"),
        collectible = true,
    )
}
