package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardType
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.opponentSecretCandidates

/** Public information associated with one active opposing Secret. */
data class PowerLogSecretObservation(
    val entityId: Int,
    val revealedCardId: String? = null,
    val deckClass: HsClass? = null,
    val creatorCardId: String? = null,
)

/** Candidate identities for the opponent's current Secret zone. */
fun activeOpponentSecretCandidates(
    match: PowerLogSnapshot,
    db: CardDatabase,
    rules: FormatRules = FormatRules(),
): List<Card> {
    val opponent = match.opponent
    if (!match.localPlayerIdentified || match.result != null || opponent.secretCount <= 0 ||
        match.format !in listOf(GameFormat.STANDARD, GameFormat.WILD)
    ) return emptyList()

    val improved = if (db.locale.equals("zhCN", ignoreCase = true)) improvedSecretsChinese else improvedSecretsEnglish
    val observed = opponent.secretObservations
    val revealed = observed.mapNotNull { clue ->
        clue.revealedCardId?.let { id -> db.byCardId(id)?.takeIf(Card::isSecretSpell) ?: improved.byCardId(id) }
    }
    val candidateDecks = mutableMapOf<HsClass, List<Card>>()
    fun deckCandidates(heroClass: HsClass): List<Card> = candidateDecks.getOrPut(heroClass) {
        opponentSecretCandidates(db, heroClass, match.format, rules)
    }
    val broadCandidates by lazy {
        val sameClass = opponent.heroClass?.let(::deckCandidates).orEmpty()
        val constructed = db.deckCards.filter { it.isSecretSpell() && it.set != "CORE_HIDDEN" && rules.isLegal(it, GameFormat.WILD) }
        sameClass + constructed + improved.allCards
    }
    return buildList {
        addAll(revealed)
        if (observed.size < opponent.secretCount) addAll(broadCandidates)
        for (clue in observed) {
            if (clue.revealedCardId != null && revealed.any { it.id == clue.revealedCardId }) continue
            val candidates = when (clue.creatorCardId) {
                "AV_113", "CORE_AV_113" -> improved.allCards
                else -> clue.deckClass?.let(::deckCandidates).orEmpty()
            }
            addAll(candidates.ifEmpty { broadCandidates })
        }
    }.distinctBy(Card::name)
}

private fun Card.isSecretSpell() =
    cardType == CardType.SPELL && "SECRET" in mechanics && name.isNotBlank()

// Noncollectible constructed cards from HearthstoneJSON's full card data.
private val improvedSecretsChinese by lazy { loadImprovedSecrets("zhCN") }
private val improvedSecretsEnglish by lazy { loadImprovedSecrets("enUS") }

private fun loadImprovedSecrets(locale: String): CardDatabase =
    PowerLogSecretObservation::class.java.getResourceAsStream("/improved-secrets.$locale.json")
        ?.bufferedReader(Charsets.UTF_8)?.use { CardDatabase.parse(it.readText(), locale) }
        ?: CardDatabase.EMPTY
