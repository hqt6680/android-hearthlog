package com.stroexd.hsdecktracker.core.powerlog

/** Exposes a pending decision after consecutive polls without changes to the decision. */
class PowerLogDecisionGate(private val quietPolls: Int = 3) {
    private var pending: PowerLogSnapshot? = null
    private var key: DecisionKey? = null
    private var emptyReads = 0
    private var emitted = false

    /** Call after consuming each non-empty read, or with null at a log reset. */
    fun onRead(snapshot: PowerLogSnapshot?) {
        val decision = snapshot?.takeIf {
            it.localPlayerIdentified && it.result == null && it.openingChoice == null
        }
        val nextKey = decision?.let {
            val choice = it.generalChoice
            val actions = it.actionOptions
            when {
                choice != null -> DecisionKey(it.gameNumber, it.turn, choice.revision, true)
                actions != null -> DecisionKey(it.gameNumber, it.turn, actions.revision, false)
                else -> null
            }
        }
        if (key != nextKey) emitted = false
        key = nextKey
        pending = decision?.takeIf {
            val choice = it.generalChoice
            if (choice != null) choice.options.isNotEmpty() else it.actionOptions?.options?.isNotEmpty() == true
        }
        emptyReads = 0
    }

    fun onEmptyRead(): PowerLogSnapshot? {
        val snapshot = pending ?: return null
        if (emitted || ++emptyReads < quietPolls) return null
        emitted = true
        return snapshot
    }

    /** Unrelated log records can arrive after a complete action packet. */
    fun onActionPoll(snapshot: PowerLogSnapshot, completeRead: Boolean): PowerLogSnapshot? {
        val previous = pending
        if (completeRead && previous != null && previous.generalChoice == null &&
            snapshot.localPlayerIdentified && snapshot.result == null && snapshot.openingChoice == null &&
            snapshot.generalChoice == null && snapshot.gameNumber == previous.gameNumber &&
            snapshot.turn == previous.turn && snapshot.actionOptions == previous.actionOptions
        ) {
            pending = snapshot
            return onEmptyRead()
        }
        onRead(snapshot)
        return null
    }

    private data class DecisionKey(val game: Long, val turn: Int, val revision: Long, val choice: Boolean)
}
