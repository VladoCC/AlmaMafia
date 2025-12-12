package org.example.lua

interface GameState

object PlayState: GameState

class WonState(val team: String): GameState

class TieState(val teams: List<String>): GameState