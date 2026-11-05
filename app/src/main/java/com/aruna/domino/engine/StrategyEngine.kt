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
 * This baseline uses the remaining unseen set and known opponent hand sizes.
 */
class StrategyEngine {
    fun analyze(state: GameSnapshot): StrategyResult {
        require(state.leftEnd in 0..6 && state.rightEnd in 0..6)
        require(state.hand.distinct().size == state.hand.size) { "Hand contains duplicate physical tiles" }
        require(state.played.intersect(state.hand.toSet()).isEmpty()) {
            "A tile cannot be both held and played"
        }
        require(state.playerHandCounts.values.all { it >= 0 }) { "Hand sizes cannot be negative" }

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
            // opening an end that opponents are statistically likely to hold.
            val score = pipReduction * 1.0 + flexibility * 0.7 -
                pressure * 1.2 + doubleBonus
            MoveScore(move, score, pipReduction, flexibility, pressure)
        }.sortedWith(
            compareByDescending<MoveScore> { it.score }
                .thenByDescending { it.pipReduction }
                .thenByDescending { it.flexibility }
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

        val matchingTiles = unseen.count { it.contains(exposedPip) }
        val unknownSlots = state.playerHandCounts.values.sum()
            .takeIf { it > 0 }
            ?: unseen.size
        val slots = unknownSlots.coerceIn(0, unseen.size)

        // Probability that at least one of the unknown opponent-held tiles
        // contains this pip, using sampling without replacement.
        var noMatchProbability = 1.0
        val nonMatching = unseen.size - matchingTiles
        repeat(slots) { index ->
            val remaining = unseen.size - index
            val remainingNonMatching = nonMatching - index
            if (remainingNonMatching <= 0) {
                noMatchProbability = 0.0
            } else {
                noMatchProbability *= remainingNonMatching.toDouble() / remaining
            }
        }
        return (1.0 - noMatchProbability).coerceIn(0.0, 1.0)
    }
}
