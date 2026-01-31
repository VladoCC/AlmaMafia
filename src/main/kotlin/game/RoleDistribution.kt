package org.example.game

import org.example.Config
import org.example.Connection
import org.example.Game
import org.example.GameId
import org.example.Role
import org.example.TeamHistory
import org.example.teamHistories
import org.example.telegram.rolesInPlay
import java.lang.Math.clamp
import kotlin.collections.forEach
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

enum class RoleDistribution {
    RANDOM, WEIGHTED
}

internal fun distributeRolesRandom(
    cons: List<Connection>,
    game: Game,
) = rolesInPlay(game).shuffled()
    .mapIndexed { index, role ->
        if (index < cons.size) {
            cons[index] to role
        } else {
            null
        }
    }
    .filterNotNull()
    .toMap()

internal fun distributeRolesWeighted(
    cons: List<Connection>,
    game: Game,
): Map<Connection, Role?> {
    val context = DistributionContext(game)
    val gameRoles = context.roles.shuffled().toMutableList()
    val playerCons = mutableListOf<Connection>()
    val botCons = mutableListOf<Connection>()
    cons.forEach { con ->
        if (con.bot) {
            botCons.add(con)
        } else {
            playerCons.add(con)
        }
    }
    val teams = context.teams.mapValues { it.value.toMutableList() }.toMutableMap()
    val conWeights = mutableMapOf<Connection, Double>()
    val conData = mutableMapOf<Connection, DistributionData>()

    var weightedSum = 0.0
    playerCons.take(context.roles.size).forEach { con ->
        val data = DistributionData(con, game, context)
        data.unfairness().let {
            conWeights[con] = it
            weightedSum += it
        }
        conData[con] = data
    }
    val sortedCons = conWeights
        .map { (elem, weight) ->
            elem to ln(Random.nextDouble()) / weight
        }
        .sortedBy { it.second }
        .map { it.first }
    val pairs = sortedCons.map { con ->
        val data = conData[con]!!
        val teamWeights = teams.filterValues { it.isNotEmpty() }.mapValues { (team, _) ->
            data.weight(team)
        }
        if (teamWeights.isEmpty()) {
            return@map con to null
        } else if (teamWeights.size == 1) {
            val onlyTeam = teamWeights.keys.first()
            val role = teams[onlyTeam]!!.removeAt(0)
            if (teams[onlyTeam]!!.isEmpty()) {
                teams.remove(onlyTeam)
            }
            gameRoles.remove(role)
            return@map con to role
        }
        val totalWeight = teamWeights.values.sum()
        val roll = Random.nextDouble(totalWeight)
        var cumulative = 0.0
        val team = teamWeights.entries.dropWhile {
            cumulative += it.value
            cumulative < roll
        }.firstOrNull()?.key
        if (team == null) {
            return@map con to null
        }
        val role = teams[team]!!.removeAt(0)
        if (teams[team]!!.isEmpty()) {
            teams.remove(team)
        }
        gameRoles.remove(role)
        con to role
    }.toMap() + if (gameRoles.isNotEmpty()) {
        gameRoles.mapIndexed { index, role ->
            if (botCons.size > index) {
                botCons[index] to role
            } else {
                null
            }
        }.filterNotNull().toMap()
    } else {
        emptyMap()
    }
    return pairs
}

private fun DistributionData.expected(team: String) = historySize * (context.teamProbability(team))
internal fun DistributionData.confidence(team: String) =
    accuracy(team).let { it / (it + config.confidenceFactor) }
internal fun DistributionData.historyStrength() = min(
    historySize.toDouble() / config.historyThreshold.toDouble(),
    1.0
)
internal fun DistributionData.accuracy(team: String) =
    if (gameHistory.isNotEmpty()) {
        gameHistory.sumOf {
            if (gameKnowledge[it.gameId]?.contains(team) == true) {
                1.0
            } else if (it.gameId !in gameUnknown) {
                0.0
            } else {
                val diff = config.unknownAccuracyMax - config.unknownAccuracyMin
                it.playerCount.let { playerCount ->
                    if (playerCount != 0)
                        (gameUnknown[it.gameId]!! / it.playerCount.toDouble()) * diff + config.unknownAccuracyMin
                    else 0.0
                }
            }
        } / gameHistory.size
    } else {
        0.0
    }

internal fun DistributionData.deviation(team: String) =
    clamp(
        confidence(team) *
                (history(team).toDouble() - expected(team)),
        -config.correctionCap,
        config.correctionCap
    )

internal fun DistributionData.weight(team: String) =
    context.teamProbability(team) * exp(-config.scaleBias * historyStrength() * deviation(team))

internal fun DistributionData.unfairness(): Double {
    var totalDeviation = 0.0
    var absDeviation = 0.0
    context.teams.keys.forEach { team ->
        val dev = deviation(team)
        totalDeviation += dev
        absDeviation += dev.absoluteValue
    }
    val expectationOffset = exp(-totalDeviation)
    return historyStrength() * expectationOffset * absDeviation + config.orderingMinWeight
}

data class DistributionData(
    val con: Connection,
    val game: Game,
    val context: DistributionContext
) {
    val gameHistory = teamHistories.find { scriptId == game.scriptId && playerId == con.playerId }
    val teamHistory by lazy { gameHistory.groupingBy { it.team }.eachCount() }
    val historySize by lazy { teamHistory.entries.sumOf { it.value } }
    val config = Config()
    val gameKnowledge: Map<GameId, Map<String, Int>>
    val gameUnknown: Map<GameId, Int>

    init {
        val knowledge = mutableMapOf<GameId, MutableMap<String, Int>>()
        val unknown = mutableMapOf<GameId, Int>()
        gameHistory.map {
            var sum = 0
            context.gameHistories(it).forEach { teamHistory ->
                val map = knowledge.getOrPut(teamHistory.gameId) { mutableMapOf() }
                if (teamHistory.team !in map) {
                    map[teamHistory.team] = teamHistory.teamSize
                    sum += teamHistory.teamSize
                }
            }
            if (sum < it.playerCount) {
                unknown[it.gameId] = it.playerCount - sum
            }
        }
        gameKnowledge = knowledge
        gameUnknown = unknown
    }

    fun history(team: String) = teamHistory[team] ?: 0
}

class DistributionContext(game: Game) {
    val roles = rolesInPlay(game)
    val teams = roles.groupBy { it.defaultTeam }
        .mapValues { (_, roles) -> roles.shuffled().toMutableList() }
    private val teamProbabilities = teams.mapValues { (_, teamRoles) ->
        if (roles.isNotEmpty()) {
            teamRoles.size.toDouble() / roles.size.toDouble()
        } else {
            0.0
        }
    }
    val historyCache = mutableMapOf<GameId, List<TeamHistory>>()

    fun teamProbability(team: String): Double = teamProbabilities[team] ?: 0.0
    fun gameHistories(teamHistory: TeamHistory): List<TeamHistory> {
        return historyCache.getOrPut(teamHistory.gameId) {
            teamHistories.find(teamHistory.gameId) { gameId == it }
        }
    }
}