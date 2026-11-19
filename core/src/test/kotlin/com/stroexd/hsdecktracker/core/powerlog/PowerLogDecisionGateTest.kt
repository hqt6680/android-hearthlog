package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PowerLogDecisionGateTest {
    private val match = PowerLogSnapshot(1, GameFormat.WILD, 12, PowerLogSide(), PowerLogSide(), null, true)
    private val rows = listOf(PowerLogActionOption(0, "END_TURN", null, "INVALID", null))
    private val actions = match.copy(actionOptions = PowerLogActionOptions(52, 1, rows))

    @Test
    fun emitsLatestNonemptyPacketOnceAfterThreeConsecutiveEmptyReads() {
        val gate = PowerLogDecisionGate()
        gate.onRead(actions)
        assertNull(gate.onEmptyRead())
        assertNull(gate.onEmptyRead())

        val updated = actions.copy(actionOptions = PowerLogActionOptions(52, 1, rows + PowerLogActionOption(1, "POWER", 15, "NONE", null)))
        gate.onRead(updated)
        assertNull(gate.onEmptyRead())
        assertNull(gate.onEmptyRead())
        assertEquals(updated, gate.onEmptyRead())
        gate.onRead(updated)
        repeat(3) { assertNull(gate.onEmptyRead()) }
    }

    @Test
    fun unrelatedLogRecordsDoNotDelayACompletePacket() {
        val gate = PowerLogDecisionGate()
        assertNull(gate.onActionPoll(actions, completeRead = true))
        assertNull(gate.onActionPoll(actions, completeRead = true))
        assertNull(gate.onActionPoll(actions, completeRead = true))
        assertEquals(actions, gate.onActionPoll(actions, completeRead = true))
        assertNull(gate.onActionPoll(actions, completeRead = true))

        val next = actions.copy(actionOptions = PowerLogActionOptions(53, 2, rows))
        assertNull(gate.onActionPoll(next, completeRead = true))
        assertNull(gate.onActionPoll(next, completeRead = true))
        assertNull(gate.onActionPoll(next, completeRead = false))
        assertNull(gate.onEmptyRead())
        assertNull(gate.onEmptyRead())
        assertEquals(next, gate.onEmptyRead())
    }

    @Test
    fun liveGateConfirmsUnchangedActionPacketOnTheNextCompletePoll() {
        val gate = PowerLogDecisionGate(quietPolls = 1)
        val updated = actions.copy(actionOptions = PowerLogActionOptions(52, 1, rows + PowerLogActionOption(1, "POWER", 15, "NONE", null)))
        assertNull(gate.onActionPoll(actions, completeRead = false))
        assertNull(gate.onActionPoll(updated, completeRead = true))
        assertEquals(updated, gate.onActionPoll(updated, completeRead = true))
        assertNull(gate.onEmptyRead())
    }

    @Test
    fun keepsDiscoverSeparateAndDropsOpeningAndClearedPackets() {
        val gate = PowerLogDecisionGate()
        val discover = actions.copy(generalChoice = PowerLogGeneralChoice(4, 1, 30, 1, 1, listOf(PowerLogChoiceOption(0, 31, "CHOICE"))))
        val opening = actions.copy(openingChoice = PowerLogOpeningChoice(2, emptyList()))
        gate.onRead(opening)
        repeat(3) { assertNull(gate.onEmptyRead()) }
        gate.onRead(actions)
        assertNull(gate.onEmptyRead())
        assertNull(gate.onEmptyRead())
        assertEquals(actions, gate.onEmptyRead())
        gate.onRead(discover)
        assertNull(gate.onEmptyRead())
        assertNull(gate.onEmptyRead())
        assertEquals(discover, gate.onEmptyRead())
        gate.onRead(null)
        repeat(3) { assertNull(gate.onEmptyRead()) }
    }
}
