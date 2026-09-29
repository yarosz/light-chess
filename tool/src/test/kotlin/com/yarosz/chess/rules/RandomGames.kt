package com.yarosz.chess.rules

import kotlin.random.Random

/** The property tests' random Games, shared with the game record's tests. */
object RandomGames {
    const val MAX_PLIES = 300

    /** A random Game from [start], with draw offers and refusals mixed in, played to its end or [maxPlies]. */
    fun game(seed: Int, start: Position = Position.START, maxPlies: Int = MAX_PLIES): Game {
        val random = Random(seed)
        var game = Game.of(start)
        while (game.result == null && game.ply < maxPlies) {
            val toMove = game.position.sideToMove
            game = when {
                game.openDrawOffer == toMove.opponent && random.nextInt(4) == 0 -> game + DrawRefusal(toMove)
                game.openDrawOffer == null && random.nextInt(40) == 0 -> game + DrawOffer(toMove)
                else -> game + game.position.legalMoves.random(random)
            }
        }
        return game
    }
}
