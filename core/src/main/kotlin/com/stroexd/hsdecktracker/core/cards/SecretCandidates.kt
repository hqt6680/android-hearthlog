package com.stroexd.hsdecktracker.core.cards

/** Collectible Secrets that can be put in a deck of the given class and format. */
fun opponentSecretCandidates(
    db: CardDatabase,
    opponent: HsClass,
    format: GameFormat,
    rules: FormatRules = FormatRules(),
): List<Card> {
    if (!opponent.isPlayable || (format != GameFormat.STANDARD && format != GameFormat.WILD)) {
        return emptyList()
    }
    return db.deckCards
        .filter { card ->
            card.cardType == CardType.SPELL &&
                "SECRET" in card.mechanics &&
                card.set != "CORE_HIDDEN" &&
                card.name.isNotBlank() &&
                card.isAllowedIn(opponent) &&
                rules.isLegal(card, format)
        }
        .sortedByDescending { it.set == "CORE" }
        .distinctBy { it.name }
        .sortedWith(compareBy(Card::cost, Card::name))
}
