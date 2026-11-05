package com.aruna.domino.engine

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
    val opponentPressure: Double
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
            val pipReduction = move.tile.pips
            val doubleBonus = if (move.tile.isDouble) 0.35 else 0.0

            // Prefer shedding pips and preserving playable options, while avoiding
            // opening an end opponents are statistically likely to hold.
            val score = pipReduction * 1.0 + flexibility * 0.7 -
                pressure * 1.2 + doubleBonus
            MoveScore(move, score, pipReduction, flexibility, pressure)
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

    private fun estimateOpponentPressure(
        exposedPip: Int,
        unseen: Set<Tile>,
        state: GameSnapshot
    ): Double {
        if (unseen.isEmpty()) return 0.0

        // A player who passed on the current board had neither open-end value.
        // Only exact-current-board passes are applied; stale passes are ignored.
        val playersKnownVoidOnPip = state.passes
            .filter { it.playedCountAtPass == state.played.size }
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
