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
    val playerHandCounts: Map<Int, Int> = emptyMap(),
    val passes: List<PassRecord> = emptyList()
)

data class MoveScore(
    val move: LegalMove,
    val score: Double,
    val pipReduction: Int,
    val flexibility: Int,
    val opponentPressure: Double
)

data class StrategyResult(
    val best: MoveScore?,
    val alternatives: List<MoveScore>,
    val legalMoves: List<LegalMove>
)

/**
 * Offline-first heuristic strategy. Hidden opponent tiles are never treated as known facts:
 * opponentPressure is only an estimate from unseen tiles and public pass records.
 */
class StrategyEngine {
    fun analyze(state: GameSnapshot): StrategyResult {
        require(state.leftEnd in 0..6 && state.rightEnd in 0..6)
        require(state.hand.distinct().size == state.hand.size) { "Hand contains duplicate physical tiles" }
        require(state.played.intersect(state.hand.toSet()).isEmpty()) { "A tile cannot be both held and played" }

        val legal = state.hand.flatMap { tile ->
            buildList {
                if (tile.contains(state.leftEnd)) add(LegalMove(tile, Side.LEFT, state.leftEnd))
                if (state.rightEnd != state.leftEnd && tile.contains(state.rightEnd)) {
                    add(LegalMove(tile, Side.RIGHT, state.rightEnd))
                }
            }
        }.distinctBy { it.tile to it.side }

        val unseen = Tile.fullSet() - state.played - state.hand.toSet()
        val scored = legal.map { move ->
            val nextPip = move.tile.otherEnd(move.exposedPip) ?: move.exposedPip
            val remainingHand = state.hand - move.tile
            val flexibility = remainingHand.sumOf { tile ->
                (if (tile.contains(state.leftEnd)) 1 else 0) +
                    (if (tile.contains(state.rightEnd)) 1 else 0) +
                    (if (tile.contains(nextPip)) 1 else 0)
            }
            val pressure = estimateOpponentPressure(nextPip, unseen, state)
            val pipReduction = move.tile.pips
            val doubleBonus = if (move.tile.isDouble) 0.35 else 0.0
            val score = pipReduction * 1.0 + flexibility * 0.7 + pressure * 1.2 + doubleBonus
            MoveScore(move, score, pipReduction, flexibility, pressure)
        }.sortedWith(compareByDescending<MoveScore> { it.score }.thenByDescending { it.pipReduction })

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
        val opponents = state.playerHandCounts.values.sum().coerceAtLeast(1)
        val matching = unseen.count { it.contains(exposedPip) }
        val unseenCount = unseen.size.coerceAtLeast(1)
        val matchingRate = matching.toDouble() / unseenCount
        val passEvidence = state.passes.count { record ->
            record.leftEnd == exposedPip || record.rightEnd == exposedPip
        }
        return (opponents * (1.0 - matchingRate) + passEvidence * 0.5).coerceAtMost(6.0)
    }
}
