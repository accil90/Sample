package com.aruna.domino.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {
    private val engine = StrategyEngine()

    @Test fun fullSetContainsTwentyEightUniqueTiles() {
        assertEquals(28, Tile.fullSet().size)
    }

    @Test fun onlyTilesMatchingAnOpenEndAreLegal() {
        val state = GameSnapshot(
            hand = listOf(Tile.of(3, 5), Tile.of(2, 4), Tile.of(6, 6)),
            played = emptySet(),
            leftEnd = 3,
            rightEnd = 6
        )
        val result = engine.analyze(state)
        assertEquals(setOf(Tile.of(3, 5), Tile.of(6, 6)), result.legalMoves.map { it.tile }.toSet())
    }

    @Test fun resultContainsBestMoveAndAtMostThreeAlternatives() {
        val state = GameSnapshot(
            hand = listOf(Tile.of(3, 5), Tile.of(3, 3), Tile.of(2, 4)),
            played = emptySet(),
            leftEnd = 3,
            rightEnd = 5
        )
        val result = engine.analyze(state)
        assertNotNull(result.best)
        assertTrue(result.alternatives.size <= 3)
    }

    @Test fun noLegalMoveReturnsEmptyRecommendation() {
        val state = GameSnapshot(
            hand = listOf(Tile.of(1, 2), Tile.of(4, 6)),
            played = emptySet(),
            leftEnd = 3,
            rightEnd = 5
        )
        val result = engine.analyze(state)
        assertEquals(null, result.best)
        assertTrue(result.legalMoves.isEmpty())
    }
}
