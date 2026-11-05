# ARUNA DOMINO PRO

A lightweight Android companion for four-player, 28-tile Gaple.

## Product direction
- Keep the screen simple; invest complexity in the analysis engine.
- The player manually records public moves and passes.
- Never claim to know opponents' hidden tiles. Predictions are estimates based on the remaining unseen tiles and public play history.
- No wagering, betting, or gambling features.

## Engine roadmap
1. Validate legal moves against the two open ends.
2. Track played tiles and pass constraints.
3. Rank legal moves using hand shedding, pip reduction, endpoint control, doubles, and future flexibility.
4. Estimate opponent tile distributions from unseen tiles and observed passes.
5. Add deterministic tests for tile accounting, legality, and ranking stability.

## Android implementation
Native Kotlin, minimal XML-based interface, offline-first. The analysis engine is kept independent from Android UI so it can be tested on its own.

## Current status
Initial project foundation. Strategy estimates are probabilistic, not guarantees.
