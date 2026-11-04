package com.sylestia.toolhubs.ludo

import kotlin.random.Random

internal data class LudoPlayer(val id: Int, val name: String, val color: Long, val isBot: Boolean, val seat: Int, val tokens: List<Int> = List(4) { -1 })
internal data class LudoState(
    val players: List<LudoPlayer>, val currentPlayer: Int = 0, val dice: Int? = null,
    val consecutiveSixes: Int = 0, val message: String = "Roll the dice to begin",
    val finishedPlayers: List<Int> = emptyList(), val gameOver: Boolean = false
)

internal object LudoEngine {
    val palette = listOf(0xFFE85D75, 0xFF36B9A0, 0xFFFFC34D, 0xFF6489F5, 0xFFAB78E8, 0xFFEF8A45)
    private val names = listOf("Ruby", "Mint", "Sunny", "Sky", "Violet", "Tangerine")

    fun newGame(playerCount: Int, humanCount: Int): LudoState {
        val seats = when (playerCount) {
            2 -> listOf(0, 3); 3 -> listOf(0, 2, 4); 4 -> listOf(0, 1, 3, 4)
            5 -> listOf(0, 1, 2, 4, 5); else -> (0..5).toList()
        }
        return LudoState(seats.mapIndexed { index, seat ->
            val playerName = if (index < humanCount) (if (humanCount > 1) "Player ${index + 1}" else "You") else "${names[seat]} Bot"
            LudoPlayer(index, playerName, palette[seat], index >= humanCount, seat)
        })
    }

    fun roll(state: LudoState, random: Random = Random.Default): LudoState {
        if (state.gameOver || state.dice != null) return state
        val value = random.nextInt(1, 7)
        if (value == 6 && state.consecutiveSixes >= 2)
            return nextTurn(state.copy(dice = value, consecutiveSixes = 0, message = "Three sixes! Turn forfeited"))
        val player = state.players[state.currentPlayer]
        if (movableTokens(player, value).isEmpty()) {
            return if (value == 6) state.copy(consecutiveSixes = state.consecutiveSixes + 1, message = "No move. Roll again!")
            else nextTurn(state.copy(dice = value, message = "No legal move"))
        }
        return state.copy(dice = value, consecutiveSixes = if (value == 6) state.consecutiveSixes + 1 else 0,
            message = if (value == 6) "A six! Choose a piece" else "Move a piece")
    }

    fun movableTokens(player: LudoPlayer, dice: Int): List<Int> = player.tokens.indices.filter { index ->
        val progress = player.tokens[index]
        if (progress == -1) dice == 6 else progress + dice <= 55
    }

    fun move(state: LudoState, tokenIndex: Int): LudoState {
        val dice = state.dice ?: return state
        val player = state.players[state.currentPlayer]
        if (tokenIndex !in movableTokens(player, dice)) return state
        val tokens = player.tokens.toMutableList()
        tokens[tokenIndex] = if (tokens[tokenIndex] == -1) 0 else tokens[tokenIndex] + dice
        val players = state.players.toMutableList()
        players[state.currentPlayer] = player.copy(tokens = tokens)
        var captured = false
        val landed = tokens[tokenIndex]
        if (landed in 0..49) {
            val global = (player.seat * 10 + landed) % 60
            if (global % 10 != 0 && global % 10 != 5) {
                players.indices.filter { it != player.id }.forEach { index ->
                    val rival = players[index]
                    players[index] = rival.copy(tokens = rival.tokens.map { progress ->
                        if (progress in 0..49 && (rival.seat * 10 + progress) % 60 == global) { captured = true; -1 } else progress
                    })
                }
            }
        }
        val winner = tokens.all { it == 55 }
        val message = when {
            winner -> "${player.name} wins the game!"
            captured -> "Piece captured! Roll again"
            landed == 55 -> "Home! Beautiful finish"
            dice == 6 -> "Six! Roll again"
            else -> "Piece moved"
        }
        val next = state.copy(players = players, dice = null, message = message)
        if (winner) return next.copy(gameOver = true, finishedPlayers = next.finishedPlayers + player.id)
        return if (dice == 6 || captured) next else nextTurn(next)
    }

    fun nextTurn(state: LudoState): LudoState {
        if (state.gameOver) return state
        var index = state.currentPlayer
        repeat(state.players.size) {
            index = (index + 1) % state.players.size
            if (index !in state.finishedPlayers) return state.copy(currentPlayer = index, dice = null, message = "Your turn")
        }
        return state.copy(gameOver = true, message = "Game complete")
    }

    fun botMove(state: LudoState, random: Random = Random.Default): LudoState {
        val rolled = roll(state, random)
        val dice = rolled.dice ?: return rolled
        val player = rolled.players[rolled.currentPlayer]
        val options = movableTokens(player, dice)
        if (options.isEmpty()) return rolled
        val chosen = options.maxByOrNull { token ->
            val progress = player.tokens[token]
            when {
                progress == -1 -> 100
                progress + dice >= 55 -> 200
                progress + dice in 0..49 && (player.seat * 10 + progress + dice) % 10 == 0 -> 80
                else -> progress
            }
        } ?: options.first()
        return move(rolled, chosen)
    }
}
