package com.aruna.domino.engine

import java.util.Random

/** A normalized domino tile; a|b and b|a represent the same physical tile. */
data class Tile private constructor(val low: Int, val high: Int) {
    val pips: Int get() = low + high
    val isDouble: Boolean get() = low == high

    fun contains(pip: Int): Boolean = low == pip || high == pip
    fun otherEnd(pip: Int): Int? = when (pip) {
        low -> high
        high -> low
        else -> null
    }

    companion object {
        fun of(a: Int, b: Int): Tile {
            require(a in 0..6 && b in 0..6) { "Pips must be between 0 and 6" }
            return Tile(minOf(a, b), maxOf(a, b))
        }

        fun fullSet(): Set<Tile> = buildSet {
            for (a in 0..6) for (b in a..6) add(of(a, b))
        }
    }
}

enum class Side { LEFT, RIGHT }

data class LegalMove(val tile: Tile, val side: Side, val exposedPip: Int)

data class PassRecord(
    val player: Int,
    val leftEnd: Int,
    val rightEnd: Int,
    val playedCountAtPass: Int
)

data class GameSnapshot(
    val hand: List<Tile>,
    val played: Set<Tile>,
    val leftEnd: Int,
    val rightEnd: Int,
    /** Opponents' known hand sizes, keyed by player id. */
    val playerHandCounts: Map<Int, Int> = emptyMap(),
    val passes: List<PassRecord> = emptyList()
)

data class MoveScore(
    val move: LegalMove,
    val score: Double,
    val pipReduction: Int,
    val flexibility: Int,
    /** Estimated chance (0..1) that at least one opponent can answer the new end. */
    val opponentPressure: Double,
    /** Estimated chance (0..1) that our remaining hand can play again after one opponent round. */
    val futurePlayChance: Double
)

data class StrategyResult(
    val best: MoveScore?,
    val alternatives: List<MoveScore>,
    val legalMoves: List<LegalMove>
)

/**
 * Offline-first heuristic strategy. Hidden opponent tiles are estimates, never known facts.
 * Passes are used conservatively: only a pass recorded at the current played-tile count
 * constrains a candidate end, because later turns may have changed the board.
 */
class StrategyEngine {
    fun analyze(state: GameSnapshot): StrategyResult {
        require(state.leftEnd in 0..6 && state.rightEnd in 0..6)
        require(state.hand.distinct().size == state.hand.size) { "Hand contains duplicate physical tiles" }
        require(state.played.intersect(state.hand.toSet()).isEmpty()) {
            "A tile cannot be both held and played"
        }
        require(state.playerHandCounts.values.all { it >= 0 }) { "Hand sizes cannot be negative" }
        require(state.playerHandCounts.values.sum() <=
            (Tile.fullSet() - state.played - state.hand.toSet()).size) {
            "Opponent hand sizes exceed the number of unseen tiles"
        }
        require(state.passes.all {
            it.leftEnd in 0..6 && it.rightEnd in 0..6 &&
                it.playedCountAtPass in 0..state.played.size
        }) { "Pass record contains invalid board data" }

        val legal = state.hand.flatMap { tile ->
            buildList {
                if (tile.contains(state.leftEnd)) add(LegalMove(tile, Side.LEFT, state.leftEnd))
                if (tile.contains(state.rightEnd)) add(LegalMove(tile, Side.RIGHT, state.rightEnd))
            }
        }.distinctBy { it.tile to it.side }

        val unseen = Tile.fullSet() - state.played - state.hand.toSet()
        val scored = legal.map { move ->
            val nextPip = move.tile.otherEnd(move.exposedPip) ?: move.exposedPip
            val nextLeft = if (move.side == Side.LEFT) nextPip else state.leftEnd
            val nextRight = if (move.side == Side.RIGHT) nextPip else state.rightEnd
            val remainingHand = state.hand - move.tile
            val flexibility = remainingHand.count { tile ->
                tile.contains(nextLeft) || tile.contains(nextRight)
            }
            val pressure = estimateOpponentPressure(nextPip, unseen, state)
            val futurePlayChance = forecastFuturePlayChance(
                move = move,
                remainingHand = remainingHand,
                unseen = unseen,
                state = state
            )
            val pipReduction = move.tile.pips
            val doubleBonus = if (move.tile.isDouble) 0.35 else 0.0

            // Prefer shedding pips and preserving playable options, while avoiding
            // opening an end opponents are statistically likely to hold.
            val score = pipReduction * 1.0 + flexibility * 0.7 -
                pressure * 1.2 + futurePlayChance * 0.8 + doubleBonus
            MoveScore(move, score, pipReduction, flexibility, pressure, futurePlayChance)
        }.sortedWith(
            compareByDescending<MoveScore> { it.score }
                .thenByDescending { it.pipReduction }
                .thenByDescending { it.flexibility }
                .thenBy { it.move.tile.low }
                .thenBy { it.move.tile.high }
                .thenBy { it.move.side.ordinal }
        )

        return StrategyResult(
            best = scored.firstOrNull(),
            alternatives = scored.drop(1).take(3),
            legalMoves = legal
        )
    }

    /**
     * Reproducible Monte Carlo look-ahead. It deals only the opponent hand sizes
     * explicitly supplied by the caller, simulates one greedy turn per opponent,
     * then checks whether our remaining hand has a legal reply.
     *
     * If hand sizes are incomplete or pass constraints make sampled deals rare,
     * the forecast falls back to the known-hand flexibility heuristic.
     */
    private fun forecastFuturePlayChance(
        move: LegalMove,
        remainingHand: List<Tile>,
        unseen: Set<Tile>,
        state: GameSnapshot
    ): Double {
        if (state.playerHandCounts.isEmpty() || remainingHand.isEmpty()) {
            return if (remainingHand.any {
                it.contains(if (move.side == Side.LEFT) move.tile.otherEnd(move.exposedPip)!! else state.leftEnd) ||
                    it.contains(if (move.side == Side.RIGHT) move.tile.otherEnd(move.exposedPip)!! else state.rightEnd)
            }) 1.0 else 0.0
        }

        val players = state.playerHandCounts.toSortedMap().filterValues { it > 0 }
        if (players.isEmpty()) return 0.0
        val required = players.values.sum()
        if (required > unseen.size) return 0.0

        val nextPip = move.tile.otherEnd(move.exposedPip) ?: move.exposedPip
        var left = if (move.side == Side.LEFT) nextPip else state.leftEnd
        var right = if (move.side == Side.RIGHT) nextPip else state.rightEnd
        val seed = (state.played.size * 31L + move.tile.low * 17L +
            move.tile.high * 13L + move.side.ordinal).toLong()
        val random = Random(seed)
        val samples = 256
        var valid = 0
        var ourNextTurnPlayable = 0

        repeat(samples) {
            val shuffled = unseen.toMutableList()
            java.util.Collections.shuffle(shuffled, random)
            var offset = 0
            val hands = linkedMapOf<Int, MutableList<Tile>>()
            players.forEach { (player, count) ->
                hands[player] = shuffled.subList(offset, offset + count).toMutableList()
                offset += count
            }

            val respectsPasses = hands.all { (player, hand) ->
                state.passes.filter { it.player == player }.all { pass ->
                    hand.none { it.contains(pass.leftEnd) || it.contains(pass.rightEnd) }
                }
            }
            if (!respectsPasses) return@repeat
            valid++

            var simLeft = left
            var simRight = right
            hands.values.forEach { hand ->
                val playable = hand.filter { it.contains(simLeft) || it.contains(simRight) }
                val chosen = playable.maxWithOrNull(
                    compareBy<Tile> { it.pips }.thenBy { it.low }.thenBy { it.high }
                )
                if (chosen != null) {
                    hand.remove(chosen)
                    when {
                        chosen.contains(simLeft) -> simLeft = chosen.otherEnd(simLeft) ?: simLeft
                        chosen.contains(simRight) -> simRight = chosen.otherEnd(simRight) ?: simRight
                    }
                }
            }
            if (remainingHand.any { it.contains(simLeft) || it.contains(simRight) }) {
                ourNextTurnPlayable++
            }
        }

        return if (valid == 0) {
            remainingHand.count { it.contains(left) || it.contains(right) }
                .toDouble() / remainingHand.size
        } else {
            (ourNextTurnPlayable.toDouble() / valid).coerceIn(0.0, 1.0)
        }
    }

    private fun estimateOpponentPressure(
        exposedPip: Int,
        unseen: Set<Tile>,
        state: GameSnapshot
    ): Double {
        if (unseen.isEmpty()) return 0.0

        // In no-draw Gaple, a player who passed while a pip was open cannot
        // later hold that pip: their hand only shrinks. Keep this evidence even
        // after other tiles are played, as long as the candidate pip was present
        // at the time of the pass.
        val playersKnownVoidOnPip = state.passes
            .filter { it.leftEnd == exposedPip || it.rightEnd == exposedPip }
            .map { it.player }
            .toSet()

        val matchingTiles = unseen.count { it.contains(exposedPip) }
        val nonMatching = unseen.size - matchingTiles
        val voidSlots = if (state.playerHandCounts.isEmpty()) 0 else {
            state.playerHandCounts
                .filterKeys { it in playersKnownVoidOnPip }
                .values
                .sum()
        }
        val activeSlots = if (state.playerHandCounts.isEmpty()) {
            unseen.size
        } else {
            state.playerHandCounts.values.sum() - voidSlots
        }.coerceIn(0, unseen.size)

        if (activeSlots == 0) return 0.0

        // Condition on void players holding only non-matching tiles. Remove those
        // slots from both the unseen pool and its non-matching portion before
        // estimating whether the remaining players can answer.
        val remainingPool = (unseen.size - voidSlots).coerceAtLeast(0)
        val remainingNonMatching = (nonMatching - voidSlots).coerceAtLeast(0)
        if (remainingPool == 0) return 0.0

        var noMatchProbability = 1.0
        repeat(activeSlots.coerceAtMost(remainingPool)) { index ->
            val poolLeft = remainingPool - index
            val nonMatchingLeft = remainingNonMatching - index
            if (nonMatchingLeft <= 0) {
                noMatchProbability = 0.0
            } else {
                noMatchProbability *= nonMatchingLeft.toDouble() / poolLeft
            }
        }

        // Keep the result bounded even if hand-size information is incomplete.
        return (1.0 - noMatchProbability).coerceIn(0.0, 1.0)
    }
}
