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
        assertTrue(result.best!!.opponentPressure in 0.0..1.0)
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

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePhysicalTileInHandIsRejected() {
        engine.analyze(
            GameSnapshot(
                hand = listOf(Tile.of(2, 5), Tile.of(5, 2)),
                played = emptySet(),
                leftEnd = 2,
                rightEnd = 4
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun tileCannotBeBothInHandAndPlayed() {
        val tile = Tile.of(1, 6)
        engine.analyze(
            GameSnapshot(
                hand = listOf(tile),
                played = setOf(tile),
                leftEnd = 1,
                rightEnd = 6
            )
        )
    }

    @Test fun rankingIsDeterministic() {
        val state = GameSnapshot(
            hand = listOf(Tile.of(0, 6), Tile.of(2, 6), Tile.of(6, 6), Tile.of(1, 3)),
            played = setOf(Tile.of(0, 0), Tile.of(0, 1)),
            leftEnd = 6,
            rightEnd = 3,
            playerHandCounts = mapOf(1 to 5, 2 to 4, 3 to 5)
        )
        assertEquals(engine.analyze(state), engine.analyze(state))
    }
    @Test fun currentPassReducesEstimatedPressureOnThePassedEnd() {
        val base = GameSnapshot(
            hand = listOf(Tile.of(2, 6)),
            played = emptySet(),
            leftEnd = 6,
            rightEnd = 2,
            playerHandCounts = mapOf(1 to 2, 2 to 2)
        )
        val withPass = base.copy(
            passes = listOf(PassRecord(player = 1, leftEnd = 6, rightEnd = 2, playedCountAtPass = 0))
        )

        val withoutPassPressure = engine.analyze(base).best!!.opponentPressure
        val withPassPressure = engine.analyze(withPass).best!!.opponentPressure
        assertTrue(withPassPressure < withoutPassPressure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun opponentHandCountsCannotExceedUnseenTiles() {
        engine.analyze(
            GameSnapshot(
                hand = listOf(Tile.of(1, 2)),
                played = emptySet(),
                leftEnd = 1,
                rightEnd = 2,
                playerHandCounts = mapOf(1 to 28)
            )
        )
    }

}
